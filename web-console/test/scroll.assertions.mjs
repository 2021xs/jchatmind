import {
  STICKY_BOTTOM_THRESHOLD_PX,
  isNearBottom,
} from "../target/deterministic-tests/utils/scroll.js";

function assert(condition, label) {
  if (!condition) {
    throw new Error(`Assertion failed: ${label}`);
  }
}

assert(
  isNearBottom({ scrollHeight: 1000, scrollTop: 600, clientHeight: 400 }),
  "exact bottom is sticky",
);
assert(
  isNearBottom({ scrollHeight: 1000, scrollTop: 550, clientHeight: 400 }),
  "50px from bottom is sticky",
);
assert(
  !isNearBottom({ scrollHeight: 1000, scrollTop: 300, clientHeight: 400 }),
  "300px from bottom is not sticky",
);
assert(
  isNearBottom(
    {
      scrollHeight: 1000,
      scrollTop: 1000 - 400 - STICKY_BOTTOM_THRESHOLD_PX,
      clientHeight: 400,
    },
  ),
  "threshold boundary is sticky",
);

console.log("sticky scroll assertions: PASS");
