import '@testing-library/jest-dom/vitest';

// jsdom has no EventSource; tests install a controllable stub.
class StubEventSource {
  static instances: StubEventSource[] = [];
  url: string;
  listeners = new Map<string, ((event: MessageEvent) => void)[]>();
  closed = false;

  constructor(url: string) {
    this.url = url;
    StubEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    const list = this.listeners.get(type) ?? [];
    list.push(listener);
    this.listeners.set(type, list);
  }

  removeEventListener(type: string, listener: (event: MessageEvent) => void) {
    const list = this.listeners.get(type) ?? [];
    this.listeners.set(type, list.filter((l) => l !== listener));
  }

  emit(type: string, data: unknown) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener(new MessageEvent(type, { data: JSON.stringify(data) }));
    }
  }

  close() {
    this.closed = true;
  }
}

// Direct assignment so per-test vi.unstubAllGlobals() calls never remove it.
(globalThis as { EventSource: unknown }).EventSource = StubEventSource;

// jsdom does not implement scrolling; the auto-scroll hook calls this directly.
window.scrollTo = () => {};

export { StubEventSource };
