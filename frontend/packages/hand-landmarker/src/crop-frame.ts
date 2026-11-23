import type { CameraCrop } from './models';

/** The smallest crop side, as a part of the image side. */
export const MIN_CROP_SIZE = 0.1;

/**
 * Returns a crop that is inside the image, or null for the full image.
 * A crop with invalid values gives the full image.
 */
export function normalizeCrop(crop: CameraCrop | null | undefined): CameraCrop | null {
    if (!crop || ![crop.x, crop.y, crop.width, crop.height].every(Number.isFinite)) {
        return null;
    }
    const width = clamp(crop.width, MIN_CROP_SIZE, 1);
    const height = clamp(crop.height, MIN_CROP_SIZE, 1);
    const x = clamp(crop.x, 0, 1 - width);
    const y = clamp(crop.y, 0, 1 - height);
    if (width === 1 && height === 1) {
        return null;
    }
    return { x, y, width, height };
}

/**
 * Returns a function that gives the part of the video frame to detect hands in:
 * the video element itself for the full image, else a canvas with the cropped frame.
 */
export function createFrameCropper(): (
    video: HTMLVideoElement,
    crop: CameraCrop | null,
) => HTMLVideoElement | HTMLCanvasElement {
    let canvas: HTMLCanvasElement | null = null;
    let context: CanvasRenderingContext2D | null = null;

    return (video, crop) => {
        if (crop === null) {
            return video;
        }
        canvas ??= document.createElement('canvas');
        context ??= canvas.getContext('2d');
        if (context === null) {
            throw new Error('The browser cannot crop the camera image.');
        }
        const sourceX = crop.x * video.videoWidth;
        const sourceY = crop.y * video.videoHeight;
        const width = Math.max(1, Math.round(crop.width * video.videoWidth));
        const height = Math.max(1, Math.round(crop.height * video.videoHeight));
        if (canvas.width !== width || canvas.height !== height) {
            canvas.width = width;
            canvas.height = height;
        }
        context.drawImage(video, sourceX, sourceY, width, height, 0, 0, width, height);
        return canvas;
    };
}

function clamp(value: number, min: number, max: number): number {
    return Math.min(max, Math.max(min, value));
}
