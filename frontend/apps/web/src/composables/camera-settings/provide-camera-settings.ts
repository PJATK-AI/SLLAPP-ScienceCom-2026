import type { CameraCrop } from '@sllapp/hand-landmarker-vue';
import { provide, readonly, shallowRef } from 'vue';
import { readCameraCrop, writeCameraCrop } from './camera-crop';
import { CameraSettingsKey, type CameraSettingsContext } from './key';

export function provideCameraSettings(): CameraSettingsContext {
    const crop = shallowRef<CameraCrop | null>(readCameraCrop());

    function saveCrop(value: CameraCrop | null): void {
        crop.value = value ? { ...value } : null;
        writeCameraCrop(crop.value);
    }

    const context: CameraSettingsContext = { crop: readonly(crop), saveCrop };
    provide(CameraSettingsKey, context);
    return context;
}
