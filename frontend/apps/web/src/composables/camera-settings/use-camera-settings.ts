import { inject } from 'vue';
import { CameraSettingsKey } from './key';

export function useCameraSettings() {
    const context = inject(CameraSettingsKey);

    if (!context) {
        throw new Error('useCameraSettings must be called inside of provideCameraSettings scope');
    }

    return context;
}
