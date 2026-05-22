# NaviSense — Architecture & Security Reference

> **Purpose:** High-level architecture, security rules, and key technical decisions.
> **Main source of truth:** [`navisense_context.md`](navisense_context.md)
> **Last Updated:** 2026-05-22

---

## 1. Android 14 Foreground Service Security Rules

These rules apply when `targetSdk = 34` (Android 14). Violations cause crashes (`ForegroundServiceStartNotAllowedException`, `SecurityException`) or silent permission denials.

| # | Rule | Manifest / Code | Pitfall |
|---|------|-----------------|---------|
| 1 | **`FOREGROUND_SERVICE_CAMERA` manifest permission** | `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />` in [`AndroidManifest.xml`](mobile/android/app/src/main/AndroidManifest.xml:6) | ❌ Using `FOREGROUND_SERVICE_TYPE_CAMERA` (with `_TYPE_` suffix) compiles but the system **silently ignores** it. Only `FOREGROUND_SERVICE_CAMERA` (without `_TYPE_`) is a valid `<uses-permission>` string. |
| 2 | **`CAMERA` runtime permission required before `startForeground()`** | Checked via `ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)` in [`MainActivity.requestDashcamPermissionAndStart()`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt:188) | ❌ On API 34, `startForeground()` with `FOREGROUND_SERVICE_TYPE_CAMERA` throws [`ForegroundServiceStartNotAllowedException`](https://developer.android.com/reference/android/app/ForegroundServiceStartNotAllowedException) if `CAMERA` permission is not granted. |
| 3 | **`ServiceCompat.startForeground()` over raw API** | [`DashcamBackgroundService.startForegroundWithNotification()`](mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt:171) uses `androidx.core.app.ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)` | ❌ Raw `startForeground()` (pre-AndroidX) does not accept the `foregroundServiceType` argument. The Compat version wraps the framework API and provides the type parameter. |
| 4 | **`RECEIVER_NOT_EXPORTED` for `BroadcastReceiver`** | [`MainActivity.onResume()`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt:154) registers via `androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)` | ❌ On API 34+, `Context.registerReceiver()` without an export flag throws `SecurityException`. Internal receivers **must** use `RECEIVER_NOT_EXPORTED`. |

### Permission Flow (Dashcam)

```
User toggles Dashcam ON
        │
        ▼
MapFragment → viewModel.toggleNavMode()
        │
        ▼
MainActivity: navMode StateFlow collector
  lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
      navMode.collect { mode ->
        DASHCAM → requestDashcamPermissionAndStart()
        SCANNER → stop service
      }
    }
  }
        │
        ▼
requestDashcamPermissionAndStart()
  ├── Check CAMERA (ContextCompat.checkSelfPermission)
  ├── Check POST_NOTIFICATIONS (API 33+)
  ├── BOTH granted → DashcamBackgroundService.start()
  ├── EITHER denied → dashcamPermissionsLauncher.launch()
  │     (RequestMultiplePermissions — single system dialog)
  │     ├── GRANTED → DashcamBackgroundService.start()
  │     └── DENIED → viewModel.setNavMode(SCANNER) + Toast
  └── (Never leaves DASHCAM mode without permission)
```

---

## 2. Kotlin Architecture & Flow Management

### StateFlow + `repeatOnLifecycle` (deprecates LiveData)

All new reactive code uses `StateFlow` with lifecycle-aware collection. This replaces the legacy `LiveData.observe()` pattern.

**Pattern (Activity):**
```kotlin
// MainActivity.kt:139
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.navMode.collect { mode ->
            when (mode) {
                NavMode.DASHCAM -> requestDashcamPermissionAndStart()
                NavMode.SCANNER -> DashcamBackgroundService.stop(this@MainActivity)
            }
        }
    }
}
```

**Pattern (Fragment):**
```kotlin
// MapFragment.kt
viewLifecycleOwner.lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.liveTrackingLocation.collectLatest { location ->
            dropDashcamMarker(location)
        }
    }
}
```

**Why `repeatOnLifecycle(STARTED)` over `observe()`:**

| Concern | `LiveData.observe()` | `StateFlow` + `repeatOnLifecycle` |
|---------|----------------------|-----------------------------------|
| Auto-cancel on background | ❌ Continues emitting | ✅ Cancels coroutine below `STARTED` |
| Testable without AndroidX | ❌ Requires `InstantTaskExecutorRule` | ✅ Pure Kotlin, `flow.test{}` |
| Null-safety | ⚠️ `LiveData<T?>` | ✅ `StateFlow<T>` (no null wrapper needed) |
| Lifecycle boundary | `RESUMED` or `STARTED` via `observeForever` | Explicit `Lifecycle.State.STARTED` in code |

### `companion object` Discipline

Each class defines **exactly one** `companion object` that holds all static constants and factory methods.

**Example — [`DashcamBackgroundService.Companion`](mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt:408):**

```kotlin
companion object {
    const val ACTION_DASHCAM_LOCATION_UPDATE = "com.navisense.action.DASHCAM_LOCATION_UPDATE"
    const val EXTRA_LATITUDE = "extra_latitude"
    const val EXTRA_LONGITUDE = "extra_longitude"
    const val EXTRA_CONFIDENCE = "extra_confidence"
    const val NOTIFICATION_ID = 1001
    const val CAPTURE_INTERVAL_MS = 5000L

    fun start(context: Context) { ... }
    fun stop(context: Context) { ... }
}
```

**Rules:**
- All `const val` (compile-time constants) go in `companion object`.
- All factory/static methods (`start()`, `stop()`, `switchLocale()`) go in `companion object`.
- Never scatter constants at the top level of the file or in nested objects.
- `const` requires a primitive/String type — use `@JvmStatic` for methods.

---

## 3. Backend VGGT-1B Mock Bypass for Windows

### Problem

The 1-billion-parameter `facebook/VGGT-1B` model fails on **Windows CPU** environments with:

```
OSError (os error 1455: The paging file is too small)
```

This occurs because PyTorch's `torch.load()` uses `mmap` for ~4 GB+ checkpoint files, and Windows' default page file is insufficient.

### Solution: `USE_MOCK = True` (default)

The [`USE_MOCK`](backend/app/main.py:21) flag in `backend/app/main.py` is set to `True` by default. When active:

1. **VGGT-1B model is never loaded.** The `if USE_MOCK:` guard at the top of [`/api/v1/vggt-odometry`](backend/app/main.py:248) returns early with simulated data.
2. **Simulated response** with random spatial offsets:

```json
{
  "status": "success",
  "camera_center_offset": {
    "x": 0.123456,
    "y": -0.045678,
    "z": 0.234567
  }
}
```

3. **MockExtractor + MockVectorDB** also provide synthetic feature vectors (768-D random, L2-normalized) and 1000 synthetic Kyiv landmarks for all other endpoints (`/api/v1/position`, `/api/visual-locate`).

### Workarounds (ranked by simplicity)

| Workaround | When to use | Steps |
|------------|-------------|-------|
| **1. `USE_MOCK = True`** (default) | Development, integration testing | Keep as-is. No model loading, no PyTorch, no crash. |
| **2. Docker container** (Linux) | Production deployment | `cd backend && docker build -t navisense-backend . && docker run -p 8000:8000 navisense-backend` |
| **3. Increase Windows paging file** | Direct `uvicorn` on Windows with real model | System Properties → Advanced → Performance → Virtual Memory → Initial: **32768 MB**, Maximum: **49152 MB**. Reboot required. |

### Health endpoint

```http
GET /api/v1/health
→ {"status": "ok", "mode": "mock"}
```

The `"mode"` field reports `"mock"` when `USE_MOCK = True`, `"production"` otherwise.

---

## References

- [`navisense_context.md`](navisense_context.md) — full single source of truth
- [`DashcamBackgroundService.kt`](mobile/android/app/src/main/java/com/navisense/core/DashcamBackgroundService.kt) — foreground service implementation
- [`MainActivity.kt`](mobile/android/app/src/main/java/com/navisense/MainActivity.kt) — permission handling and lifecycle management
- [`MainViewModel.kt`](mobile/android/app/src/main/java/com/navisense/ui/MainViewModel.kt) — StateFlow definitions and sensor-fusion pipeline
- [`backend/app/main.py`](backend/app/main.py) — FastAPI backend with `USE_MOCK` flag
- [Android 14 Foreground Service Types](https://developer.android.com/about/versions/14/changes/fgs-types#camera)
- [Android 14 Foreground Service Start Restrictions](https://developer.android.com/about/versions/14/changes/restrict-fgs-start#camera)
- [ServiceCompat.startForeground()](https://developer.android.com/reference/androidx/core/app/ServiceCompat#startForeground(android.app.Service,int,android.app.Notification,int))
