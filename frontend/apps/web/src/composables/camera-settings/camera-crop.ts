import { normalizeCrop, type CameraCrop } from '@sllapp/hand-landmarker-vue';

export const CAMERA_CROP_STORAGE_KEY = 'sllapp-camera-crop';
export const MAX_CROP_ZOOM = 3;

/**
 * A crop has the same side in normalized units on both axes, so it keeps the camera's aspect ratio.
 * Then the hand landmarks that the model gets are not stretched.
 */
export function cropFromZoom(zoom: number, centerX: number, centerY: number): CameraCrop | null {
    const side = 1 / Math.min(MAX_CROP_ZOOM, Math.max(1, zoom));
    return normalizeCrop({
        x: centerX - side / 2,
        y: centerY - side / 2,
        width: side,
        height: side,
    });
}

export function cropZoom(crop: CameraCrop | null): number {
    return crop ? 1 / crop.width : 1;
}

export function cropCenter(crop: CameraCrop | null): { x: number; y: number } {
    return crop ? { x: crop.x + crop.width / 2, y: crop.y + crop.height / 2 } : { x: 0.5, y: 0.5 };
}

/** Moves the crop by a part of the image size and keeps it inside the image. */
export function moveCrop(crop: CameraCrop | null, dx: number, dy: number): CameraCrop | null {
    if (!crop) return null;
    return normalizeCrop({ ...crop, x: crop.x + dx, y: crop.y + dy });
}

export function readCameraCrop(): CameraCrop | null {
    try {
        const value = localStorage.getItem(CAMERA_CROP_STORAGE_KEY);
        if (!value) return null;
        const crop: unknown = JSON.parse(value);
        if (typeof crop !== 'object' || crop === null) return null;
        const { x, y, width } = crop as Record<string, unknown>;
        if (typeof x !== 'number' || typeof y !== 'number' || typeof width !== 'number') {
            return null;
        }
        // Use the width on both axes, so a stored crop always keeps the aspect ratio.
        const side = Math.max(1 / MAX_CROP_ZOOM, width);
        return normalizeCrop({ x, y, width: side, height: side });
    } catch {
        return null;
    }
}

export function writeCameraCrop(crop: CameraCrop | null): void {
    try {
        if (crop) localStorage.setItem(CAMERA_CROP_STORAGE_KEY, JSON.stringify(crop));
        else localStorage.removeItem(CAMERA_CROP_STORAGE_KEY);
    } catch {
        // Storage can be unavailable (private mode); the setting then lasts for this visit only.
    }
}
