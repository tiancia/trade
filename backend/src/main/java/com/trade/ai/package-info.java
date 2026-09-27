/**
 * Shared AI infrastructure that is not owned by one business domain.
 *
 * <p>Provider-neutral contracts live in {@code application.port}, audit values
 * in {@code domain.model}, and MyBatis adapters in {@code infrastructure.persistence}.
 * Domain-specific prompts and parsers stay in each domain's {@code application.decision}.</p>
 */
package com.trade.ai;
