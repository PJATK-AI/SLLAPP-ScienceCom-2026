import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, onTestFinished, vi } from 'vitest';
import App from './App.vue';

afterEach(() => {
    vi.unstubAllGlobals();
});

it('starts with the player setup and hides diagnostics', () => {
    const wrapper = mount(App);
    expect(wrapper.get('form').text()).toContain('Zaczynamy');
    expect(wrapper.find('video').exists()).toBe(false);
    expect(wrapper.find('details').exists()).toBe(false);
    wrapper.unmount();
});

it('opens the camera settings from the gear button in the header', async () => {
    // No camera in jsdom: the dialog then shows its camera error, which is enough here.
    vi.stubGlobal('navigator', {});
    // jsdom has no modal dialogs.
    Object.assign(HTMLDialogElement.prototype, {
        showModal(this: HTMLDialogElement) {
            this.setAttribute('open', '');
        },
        close(this: HTMLDialogElement) {
            this.removeAttribute('open');
        },
    });
    onTestFinished(() => {
        delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
        delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).close;
    });
    const wrapper = mount(App);
    const gear = wrapper.get('.header-actions button[aria-label="Ustawienia kamery"]');
    expect(gear.element).toBe(wrapper.get('.header-actions').element.lastElementChild);

    await gear.trigger('click');
    await flushPromises();

    expect(gear.attributes('aria-expanded')).toBe('true');
    expect(wrapper.get('dialog').text()).toContain('Ustawienia kamery');
    expect(wrapper.find('dialog video').exists()).toBe(true);
    wrapper.unmount();
});
