export const STICKY_BOTTOM_THRESHOLD_PX = 120;

export interface ScrollMetrics {
  scrollHeight: number;
  scrollTop: number;
  clientHeight: number;
}

export function isNearBottom(
  { scrollHeight, scrollTop, clientHeight }: ScrollMetrics,
  threshold = STICKY_BOTTOM_THRESHOLD_PX,
): boolean {
  return scrollHeight - scrollTop - clientHeight <= threshold;
}
