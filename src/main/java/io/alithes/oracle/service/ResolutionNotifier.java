package io.alithes.oracle.service;

import io.alithes.oracle.domain.Resolution;

/**
 * Port for delivering resolved question notifications to external subscribers.
 */
public interface ResolutionNotifier {

    void notify(String webhookUrl, Resolution resolution);
}
