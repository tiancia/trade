/**
 * AI short-story generation domain.
 *
 * <p>The generation flow collects trend context, asks the model for a topic
 * plan, drafts sections one by one, normalizes parse fallbacks, and writes the
 * finished artifact to the configured story output directory. The background
 * entry is {@link com.trade.story.scheduler.AiStoryScheduler}; the full use
 * case is {@link com.trade.story.application.AiStoryService}.</p>
 */
package com.trade.story;
