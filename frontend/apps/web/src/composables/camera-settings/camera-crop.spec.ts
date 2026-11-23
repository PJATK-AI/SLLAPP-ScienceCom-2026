import { afterEach, describe, expect, it } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import {
    CAMERA_CROP_STORAGE_KEY,
    cropCenter,
    cropFromZoom,
    cropZoom,
    MAX_CROP_ZOOM,
    moveCrop,
    readCameraCrop,
    writeCameraCrop,
} from './camera-crop';
import { provideCameraSettings } from './provide-camera-settings';
import { useCameraSettings } from './use-camera-settings';

afterEach(() => {
    localStorage.clear();
});

describe('camera crop', () => {
    it('builds a crop with the same side on both axes around the center', () => {
        expect(cropFromZoom(2, 0.5, 0.5)).toEqual({ x: 0.25, y: 0.25, width: 0.5, height: 0.5 });
        expect(cropFromZoom(1, 0.3, 0.7)).toBeNull();
    });

    it('limits the zoom and keeps the crop inside the image', () => {
        const crop = cropFromZoom(10, 0, 1);
        expect(cropZoom(crop)).toBeCloseTo(MAX_CROP_ZOOM);
        expect(crop?.x).toBe(0);
        expect(crop!.y + crop!.height).toBeCloseTo(1);
    });

    it('moves a crop inside the image', () => {
        const crop = cropFromZoom(2, 0.5, 0.5);
        expect(moveCrop(crop, 0.1, -0.1)).toEqual({ x: 0.35, y: 0.15, width: 0.5, height: 0.5 });
        expect(moveCrop(crop, 1, 1)).toEqual({ x: 0.5, y: 0.5, width: 0.5, height: 0.5 });
        expect(moveCrop(null, 0.1, 0.1)).toBeNull();
    });

    it('gives the center and zoom of the full image when there is no crop', () => {
        expect(cropCenter(null)).toEqual({ x: 0.5, y: 0.5 });
        expect(cropZoom(null)).toBe(1);
    });
});

describe('camera crop storage', () => {
    it('saves and reads a crop', () => {
        const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.5 };
        writeCameraCrop(crop);
        expect(readCameraCrop()).toEqual(crop);
    });

    it('removes the stored crop for the full image', () => {
        writeCameraCrop({ x: 0.1, y: 0.2, width: 0.5, height: 0.5 });
        writeCameraCrop(null);
        expect(localStorage.getItem(CAMERA_CROP_STORAGE_KEY)).toBeNull();
        expect(readCameraCrop()).toBeNull();
    });

    it('ignores invalid stored values', () => {
        for (const value of ['not json', 'null', '{"x":"a","y":0,"width":0.5}', '[]']) {
            localStorage.setItem(CAMERA_CROP_STORAGE_KEY, value);
            expect(readCameraCrop()).toBeNull();
        }
    });

    it('restores the aspect ratio of a changed stored crop', () => {
        localStorage.setItem(
            CAMERA_CROP_STORAGE_KEY,
            JSON.stringify({ x: 0.9, y: 0, width: 0.5, height: 0.2 }),
        );
        expect(readCameraCrop()).toEqual({ x: 0.5, y: 0, width: 0.5, height: 0.5 });
    });
});

describe('camera settings context', () => {
    function mountContext() {
        let context!: ReturnType<typeof useCameraSettings>;
        const Child = defineComponent({
            setup() {
                context = useCameraSettings();
                return () => null;
            },
        });
        const Parent = defineComponent({
            setup() {
                provideCameraSettings();
                return () => h(Child);
            },
        });
        const wrapper = mount(Parent);
        return { context, wrapper };
    }

    it('starts with the stored crop', () => {
        const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.5 };
        writeCameraCrop(crop);
        const { context, wrapper } = mountContext();
        expect(context.crop.value).toEqual(crop);
        wrapper.unmount();
    });

    it('updates and stores a saved crop', () => {
        const { context, wrapper } = mountContext();
        const crop = { x: 0.25, y: 0.25, width: 0.5, height: 0.5 };
        context.saveCrop(crop);

        expect(context.crop.value).toEqual(crop);
        expect(readCameraCrop()).toEqual(crop);
        wrapper.unmount();
    });

    it('requires the provider', () => {
        const Orphan = defineComponent({
            setup() {
                useCameraSettings();
                return () => null;
            },
        });
        expect(() => mount(Orphan)).toThrow('provideCameraSettings');
    });
});
