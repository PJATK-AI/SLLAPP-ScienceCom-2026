import { flushPromises, mount } from '@vue/test-utils';
import { HandTrackerError } from '@sllapp/hand-landmarker-vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { nextTick, ref, shallowRef } from 'vue';
import CameraSettingsDialog from './CameraSettingsDialog.vue';
import { CameraSettingsKey, type CameraSettingsContext } from '@/composables/camera-settings/key';
import type { CameraCrop } from '@sllapp/hand-landmarker-vue';

const camera = vi.hoisted(() => ({ openCamera: vi.fn(), stopStream: vi.fn() }));
vi.mock('@sllapp/hand-landmarker-vue', async (original) => ({
    ...(await original<typeof import('@sllapp/hand-landmarker-vue')>()),
    openCamera: camera.openCamera,
    stopStream: camera.stopStream,
}));

const stream = {} as MediaStream;

beforeEach(() => {
    camera.openCamera.mockResolvedValue(stream);
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
    // jsdom has no modal dialogs; these keep the open attribute in step with the dialog API.
    Object.assign(HTMLDialogElement.prototype, {
        showModal(this: HTMLDialogElement) {
            this.setAttribute('open', '');
        },
        close(this: HTMLDialogElement) {
            this.removeAttribute('open');
        },
    });
});

afterEach(() => {
    delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
    delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).close;
    vi.restoreAllMocks();
    camera.openCamera.mockReset();
    camera.stopStream.mockReset();
});

function mountDialog(savedCrop: CameraCrop | null = null) {
    const crop = shallowRef(savedCrop);
    const saveCrop = vi.fn((value: CameraCrop | null) => {
        crop.value = value;
    });
    const settings: CameraSettingsContext = { crop, saveCrop };
    const open = ref(false);
    const wrapper = mount(CameraSettingsDialog, {
        props: {
            open: open.value,
            'onUpdate:open': (value: boolean) => {
                open.value = value;
                void wrapper.setProps({ open: value });
            },
        },
        global: { provide: { [CameraSettingsKey]: settings } },
        attachTo: document.body,
    });
    return { wrapper, open, saveCrop };
}

async function openDialog(wrapper: ReturnType<typeof mountDialog>['wrapper']) {
    await wrapper.setProps({ open: true });
    await flushPromises();
}

it('renders no camera preview while closed', () => {
    const { wrapper } = mountDialog();
    expect(wrapper.find('video').exists()).toBe(false);
    expect(camera.openCamera).not.toHaveBeenCalled();
    wrapper.unmount();
});

it('opens the camera preview with the crop frame', async () => {
    const { wrapper } = mountDialog();
    await openDialog(wrapper);

    expect(wrapper.get('dialog').attributes('open')).toBeDefined();
    expect(camera.openCamera).toHaveBeenCalled();
    expect(wrapper.get('video').element.srcObject).toBe(stream);
    expect(wrapper.find('.crop').exists()).toBe(true);
    wrapper.unmount();
});

it('saves the crop set with the zoom slider', async () => {
    const { wrapper, saveCrop, open } = mountDialog();
    await openDialog(wrapper);

    await wrapper.get('input[type="range"]').setValue('2');
    expect(wrapper.get('output').text()).toBe('2,0×');
    await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Zapisz')!
        .trigger('click');

    expect(saveCrop).toHaveBeenCalledWith({ x: 0.25, y: 0.25, width: 0.5, height: 0.5 });
    expect(open.value).toBe(false);
    await nextTick();
    expect(camera.stopStream).toHaveBeenCalledWith(stream);
    wrapper.unmount();
});

it('moves the crop with the arrow keys in the mirrored preview', async () => {
    const { wrapper, saveCrop } = mountDialog({ x: 0.25, y: 0.25, width: 0.5, height: 0.5 });
    await openDialog(wrapper);

    await wrapper.get('.crop').trigger('keydown', { key: 'ArrowLeft' });
    await wrapper.get('.crop').trigger('keydown', { key: 'ArrowDown' });
    await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Zapisz')!
        .trigger('click');

    const [saved] = saveCrop.mock.lastCall!;
    expect(saved?.x).toBeCloseTo(0.27);
    expect(saved?.y).toBeCloseTo(0.27);
    wrapper.unmount();
});

it('resets to the full image', async () => {
    const { wrapper, saveCrop } = mountDialog({ x: 0.25, y: 0.25, width: 0.5, height: 0.5 });
    await openDialog(wrapper);

    await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Pełny obraz')!
        .trigger('click');
    await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Zapisz')!
        .trigger('click');

    expect(saveCrop).toHaveBeenCalledWith(null);
    wrapper.unmount();
});

it('does not save the crop on cancel', async () => {
    const { wrapper, saveCrop, open } = mountDialog();
    await openDialog(wrapper);

    await wrapper.get('input[type="range"]').setValue('2');
    await wrapper
        .findAll('button')
        .find((button) => button.text() === 'Anuluj')!
        .trigger('click');

    expect(saveCrop).not.toHaveBeenCalled();
    expect(open.value).toBe(false);
    wrapper.unmount();
});

it('shows a message when camera access is denied', async () => {
    camera.openCamera.mockRejectedValue(new HandTrackerError('camera-denied', 'Denied'));
    const { wrapper } = mountDialog();
    await openDialog(wrapper);

    expect(wrapper.get('[role="alert"]').text()).toContain('Zezwól na dostęp do kamery');
    expect(wrapper.find('.crop').exists()).toBe(false);
    wrapper.unmount();
});
