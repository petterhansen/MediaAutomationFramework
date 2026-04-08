package com.framework.core;

import com.framework.api.DownloadSourceProvider;
import com.framework.common.DownloadRequest;
import com.framework.core.queue.QueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Service that handles dynamic source detection and task building for downloads.
 * Plugins can register their DownloadSourceProvider instances here.
 */
public class SourceDetectionService {
    private static final Logger logger = LoggerFactory.getLogger(SourceDetectionService.class);
    private final List<DownloadSourceProvider> providers = new ArrayList<>();
    private DownloadSourceProvider defaultProvider;

    /**
     * Registers a new DownloadSourceProvider.
     */
    public void registerProvider(DownloadSourceProvider provider) {
        providers.add(provider);
        logger.info("Registered Source Provider: {}", provider.getName());
    }

    /**
     * Unregisters a DownloadSourceProvider.
     */
    public void unregisterProvider(DownloadSourceProvider provider) {
        providers.remove(provider);
        logger.info("Unregistered Source Provider: {}", provider.getName());
    }

    /**
     * Registers a provider to be used as a fallback if no other provider matches.
     * Only one default provider can be set at a time.
     */
    public void registerDefaultProvider(DownloadSourceProvider provider) {
        this.defaultProvider = provider;
        logger.info("Registered Default Source Provider: {}", provider.getName());
    }

    /**
     * Maps the download request to a fully built QueueTask by finding the appropriate provider.
     * 
     * @param req The download request.
     * @param chatId The chat ID of the initiator.
     * @param threadId The thread ID of the initiator.
     * @throws IllegalArgumentException if no provider can handle the request.
     */
    public QueueTask createTask(DownloadRequest req, long chatId, Integer threadId) {
        DownloadSourceProvider matchedProvider = null;

        if (req.source() != null) {
            // Explicit source was provided, find matching provider
            for (DownloadSourceProvider p : providers) {
                if (p.getName().equalsIgnoreCase(req.source())) {
                    matchedProvider = p;
                    break;
                }
            }
            if (matchedProvider == null && defaultProvider != null && defaultProvider.getName().equalsIgnoreCase(req.source())) {
                matchedProvider = defaultProvider;
            }
        } else {
            // Auto-detect source from query
            for (DownloadSourceProvider p : providers) {
                if (p.canHandle(req.query())) {
                    matchedProvider = p;
                    break;
                }
            }
            // Use default if nothing matched
            if (matchedProvider == null && defaultProvider != null) {
                matchedProvider = defaultProvider;
            }
        }

        if (matchedProvider == null) {
            throw new IllegalArgumentException("No source provider found that can handle this request.");
        }

        logger.info("Using provider '{}' for request: {}", matchedProvider.getName(), req);
        return matchedProvider.createTask(req, chatId, threadId);
    }
}
