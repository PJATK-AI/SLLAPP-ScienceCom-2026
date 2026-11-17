import { mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import SessionPanel from './SessionPanel.vue';
class Socket extends EventTarget {
    static OPEN = 1;
    static CLOSING = 2;
    static last: Socket;
    readyState = 0;
    bufferedAmount = 0;
    send = vi.fn<(text: string) => void>();
    close = vi.fn();
    constructor() {
        super();
        Socket.last = this;
    }
    message(value: unknown) {
        this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(value) }));
    }
}
afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});
it('creates a selected session, sends both test samples and displays acknowledgements', async () => {
    vi.stubGlobal('WebSocket', Socket);
    vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
    const wrapper = mount(SessionPanel);
    await wrapper.get('input').setValue('Ola');
    await wrapper.get('select').setValue('LEFT');
    await wrapper.get('form').trigger('submit');
    const socket = Socket.last;
    socket.readyState = 1;
    socket.dispatchEvent(new Event('open'));
    expect(JSON.parse(socket.send.mock.calls[0]![0])).toEqual({
        type: 'create_session',
        nickname: 'Ola',
        selectedHand: 'LEFT',
    });
    socket.message({
        type: 'game_state',
        sessionId: '12345678-1234-1234-1234-123456789abc',
        status: 'CREATED',
        nickname: 'Ola',
        selectedHand: 'LEFT',
        positionId: null,
    });
    await nextTick();
    const button = (text: string) => wrapper.findAll('button').find((b) => b.text() === text)!;
    await button('Wyślij testową dłoń').trigger('click');
    const hand = JSON.parse(socket.send.mock.calls[1]![0]);
    expect(hand.hand.handedness).toBe('LEFT');
    expect(hand.hand.landmarks).toHaveLength(21);
    await button('Wyślij brak dłoni').trigger('click');
    expect(JSON.parse(socket.send.mock.calls[2]![0])).toEqual({
        type: 'no_hand',
        seq: 1,
        positionId: null,
    });
    socket.message({ type: 'sample_ack', seq: 1 });
    await nextTick();
    expect(wrapper.get('[data-testid="ack-seq"]').text()).toBe('1');
    socket.bufferedAmount = 1;
    await button('Wyślij brak dłoni').trigger('click');
    expect(wrapper.text()).toContain('Próbka pominięta');
    expect(wrapper.get('[data-testid="sent-seq"]').text()).toBe('1');
    await button('Rozłącz').trigger('click');
    expect(button('Wyślij testową dłoń').attributes('disabled')).toBeDefined();
    expect(button('Wznów sesję').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
});
