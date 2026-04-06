/**
 * 비용 포맷팅 유틸. 계산은 백엔드(ModelPricingService)가 담당.
 * 프론트엔드는 백엔드에서 받은 costUsd를 표시만 한다.
 */
export function formatCostUsd(costUsd: number): string {
  if (costUsd < 0.001) return `$${costUsd.toFixed(6)}`;
  if (costUsd < 0.01) return `$${costUsd.toFixed(4)}`;
  return `$${costUsd.toFixed(3)}`;
}

export function formatCostKrw(costUsd: number, exchangeRate: number): string {
  const krw = costUsd * exchangeRate;
  return `${Math.round(krw).toLocaleString()}`;
}
