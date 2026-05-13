import os
import torch
import torch.nn.functional as F
from glob import glob

from vggt.utils.load_fn import load_and_preprocess_images_square

# Оскільки файли лежать в одній папці, імпортуємо напряму:
from vggt_processor import VGGTProcessor

def run_test():
    print("🚀 Старт тестування VGGTProcessor...")
    processor = VGGTProcessor()
    
    # Автоматично визначаємо шлях до папки app, де ми зараз знаходимося
    current_dir = os.path.dirname(os.path.abspath(__file__))
    # Шукаємо картинки в app/vggt/imgs
    frame_dir = os.path.join(current_dir, "vggt", "imgs") 
    
    image_paths = sorted(glob(os.path.join(frame_dir, "*.png")))
    
    if not image_paths:
        print(f"❌ Картинки не знайдені в {frame_dir}! Перевір шлях.")
        return

    print(f"📸 Знайдено {len(image_paths)} кадрів. Обробляємо...")
    images, _ = load_and_preprocess_images_square(image_paths, 518)
    
    images_resized = F.interpolate(images, size=(518,518), mode="bilinear", align_corners=False)
    images_resized = images_resized.unsqueeze(0)
    
    try:
        offset = processor.get_relative_position(images_resized)
        print("\n✅ ТЕСТ ПРОЙДЕНО УСПІШНО!")
        print(f"📍 Отримані координати зміщення [X, Y, Z]: {offset}\n")
    except Exception as e:
        print(f"\n❌ ПОМИЛКА ПІД ЧАС ВИКОНАННЯ:\n{e}")

if __name__ == "__main__":
    run_test()