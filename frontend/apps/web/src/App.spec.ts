import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import App from './App.vue';
it('starts with the player setup and hides diagnostics', () => {
    const wrapper = mount(App);
    expect(wrapper.get('form').text()).toContain('Utwórz sesję');
    expect(wrapper.find('video').exists()).toBe(false);
    expect(wrapper.find('details').exists()).toBe(false);
    wrapper.unmount();
});
