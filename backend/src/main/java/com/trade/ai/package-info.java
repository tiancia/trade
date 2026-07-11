/**
 * Shared AI infrastructure that is not owned by one business domain.
 *
 * <p>Provider-neutral contracts live in {@code audit}; MyBatis adapters live in
 * {@code persistence}. Domain-specific prompts and response parsers stay in
 * each domain's {@code decision} package.</p>
 */
package com.trade.ai;
