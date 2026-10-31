// jest-dom adds custom matchers for asserting on DOM nodes.
// learn more: https://github.com/testing-library/jest-dom
import '@testing-library/jest-dom/vitest';

// Testing Library's waitFor moves fake timers on only when it finds a global `jest` with advanceTimersByTime; under Vitest there
// is none, so a waitFor inside vi.useFakeTimers() waits for a clock that never moves. This one function is all it looks for.
globalThis.jest = { advanceTimersByTime: (ms) => vi.advanceTimersByTime(ms) };
