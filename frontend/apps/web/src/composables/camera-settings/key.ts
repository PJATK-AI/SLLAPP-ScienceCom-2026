import type { CameraCrop } from '@sllapp/hand-landmarker-vue';
import type { InjectionKey, Ref } from 'vue';

export interface CameraSettingsContext {
    /** The saved crop of the camera image, or null for the full image. */
    crop: Readonly<Ref<CameraCrop | null>>;
    /** Saves the crop in this browser and uses it in the game camera at once. */
    saveCrop: (crop: CameraCrop | null) => void;
}

export const CameraSettingsKey: InjectionKey<CameraSettingsContext> = Symbol('camera-settings');
