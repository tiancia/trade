package com.trade.polymarket.port;

/** Durable single-account submission barrier; unresolved execution never expires automatically. */
public interface PolymarketExecutionGuard {
    PolymarketExecutionGuard UNAVAILABLE = new PolymarketExecutionGuard() {
        @Override
        public boolean reserve(Long decisionId) {
            throw new IllegalStateException("Persistent Polymarket execution guard is unavailable");
        }

        @Override
        public void release(Long decisionId) {
            throw new IllegalStateException("Persistent Polymarket execution guard is unavailable");
        }
    };

    /** Commits ownership before an external submission; false means another decision remains unresolved. */
    boolean reserve(Long decisionId);

    /** Clears only this decision's reservation after its definitive outcome is durably audited. */
    void release(Long decisionId);
}
