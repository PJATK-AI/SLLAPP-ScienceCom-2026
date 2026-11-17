import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import App from './App.vue';
it('shows the session test panel with sample controls disabled', () => {
    const wrapper = mount(App);
    expect(wrapper.text()).toContain('Test połączenia');
    const button = wrapper.findAll('button').find((b) => b.text() === 'Wyślij testową dłoń')!;
    expect(button.attributes('disabled')).toBeDefined();
    wrapper.unmount();
});
