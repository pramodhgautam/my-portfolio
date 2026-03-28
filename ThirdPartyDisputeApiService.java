package com.example.disputes.service;

import com.example.disputes.entity.ClientFeatureRequest;
import com.example.disputes.repository.ClientFeatureRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@Transactional
public class ThirdPartyDisputeApiService {

    private static final Logger logger = LoggerFactory.getLogger(ThirdPartyDisputeApiService.class);

    @Autowired
    private ClientFeatureRequestRepository clientFeatureRequestRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${dispute.api.base-url:http://172.31.203.32/}")
    private String apiBaseUrl;

    @Value("${dispute.api.token:dkRHM3dURnFweWJ2V2hDRUtyOFdsWXJKMEplWnByZmMzallJYTdCOGZ5a2xkZGVsTHA3RGNEMC1qdllCdVZmb2MxTQ==}")
    private String apiToken;

    @Value("${dispute.api.endpoint:api/disputes}")
    private String apiEndpoint;

    /**
     * Submit dispute to third-party API with dispute data from JSON
     * Sends only the data field from dispute JSON to third-party system
     */
    public DisputeApiResponse submitDisputeToThirdParty(Long disputeId) {
        logger.info("Submitting dispute ID: {} to third-party API", disputeId);

        try {
            // Fetch the dispute
            Optional<ClientFeatureRequest> disputeOptional = clientFeatureRequestRepository.findById(disputeId);

            if (disputeOptional.isEmpty()) {
                logger.warn("Dispute not found: {}", disputeId);
                return DisputeApiResponse.failure(
                        disputeId,
                        "Dispute not found",
                        "NOT_FOUND",
                        null
                );
            }

            ClientFeatureRequest dispute = disputeOptional.get();

            // Check if dispute is in INITIATED status
            if (!"INITIATED".equals(dispute.getStatus())) {
                logger.warn("Dispute {} is not in INITIATED status. Current status: {}", disputeId, dispute.getStatus());
                return DisputeApiResponse.failure(
                        disputeId,
                        "Dispute must be in INITIATED status to submit",
                        "INVALID_STATUS",
                        "Current status: " + dispute.getStatus()
                );
            }

            logger.debug("Dispute {} found in INITIATED status. Preparing API call", disputeId);

            // Get dispute data (already in the correct format)
            JsonNode disputeData = dispute.getData();

            if (disputeData == null) {
                logger.error("Dispute {} has no data", disputeId);
                return DisputeApiResponse.failure(
                        disputeId,
                        "Dispute has no data",
                        "NO_DATA",
                        null
                );
            }

            // Prepare HTTP headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiToken);
            headers.set("X-Request-ID", generateRequestId());
            headers.set("User-Agent", "BANKX/1.0");

            // Create HTTP request with dispute data as JSON string
            String requestBody = objectMapper.writeValueAsString(disputeData);
            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

            logger.info("Calling third-party API at: {}", apiBaseUrl + apiEndpoint);
            logger.debug("Request payload: {}", requestBody);

            // Call third-party API
            String apiUrl = apiBaseUrl + apiEndpoint;
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            logger.debug("API Response Status: {}", response.getStatusCode());
            logger.debug("API Response Body: {}", response.getBody());

            // Handle response
            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                return handleSuccessResponse(dispute, response);
            } else {
                return handleErrorResponse(dispute, response.getStatusCode(), response.getBody());
            }

        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized e) {
            logger.error("Authentication failed with third-party API", e);
            return DisputeApiResponse.failure(
                    disputeId,
                    "Authentication failed",
                    "UNAUTHORIZED",
                    e.getMessage()
            );

        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
            logger.error("Bad request to third-party API", e);
            return DisputeApiResponse.failure(
                    disputeId,
                    "Invalid request format",
                    "BAD_REQUEST",
                    e.getMessage()
            );

        } catch (org.springframework.web.client.ResourceAccessException e) {
            logger.error("Timeout or connection error with third-party API", e);
            return DisputeApiResponse.retryable(
                    disputeId,
                    "Connection timeout, please retry",
                    "TIMEOUT"
            );

        } catch (Exception e) {
            logger.error("Error submitting dispute to third-party API", e);
            return DisputeApiResponse.failure(
                    disputeId,
                    "Error: " + e.getMessage(),
                    "ERROR",
                    e.getMessage()
            );
        }
    }

    /**
     * Handle successful API response
     */
    private DisputeApiResponse handleSuccessResponse(ClientFeatureRequest dispute, ResponseEntity<String> response) {
        logger.info("Dispute {} submitted successfully to third-party API", dispute.getId());

        try {
            // Update dispute status to SUBMITTED
            dispute.setStatus("SUBMITTED");
            dispute.setLchgTime(OffsetDateTime.now());
            clientFeatureRequestRepository.save(dispute);

            logger.info("Dispute {} status updated to SUBMITTED", dispute.getId());

            return DisputeApiResponse.success(
                    dispute.getId(),
                    "Dispute submitted successfully",
                    response.getBody()
            );

        } catch (Exception e) {
            logger.error("Error updating dispute status after successful API call", e);
            return DisputeApiResponse.success(
                    dispute.getId(),
                    "Dispute submitted to API but error updating local status",
                    response.getBody()
            );
        }
    }

    /**
     * Handle error API response
     */
    private DisputeApiResponse handleErrorResponse(ClientFeatureRequest dispute, HttpStatus statusCode, String responseBody) {
        logger.error("API returned error status {} for dispute {}", statusCode, dispute.getId());

        // Check if error is retryable
        if (isRetryable(statusCode)) {
            logger.warn("Error is retryable for dispute {}", dispute.getId());
            return DisputeApiResponse.retryable(
                    dispute.getId(),
                    "Temporary error from third-party API, please retry",
                    statusCode.toString()
            );
        } else {
            // Update dispute status to FAILED
            dispute.setStatus("FAILED");
            dispute.setRepairMessage("API error: " + statusCode + " - " + responseBody);
            dispute.setLchgTime(OffsetDateTime.now());
            clientFeatureRequestRepository.save(dispute);

            logger.info("Dispute {} status updated to FAILED", dispute.getId());

            return DisputeApiResponse.failure(
                    dispute.getId(),
                    "API error: " + statusCode,
                    statusCode.toString(),
                    responseBody
            );
        }
    }

    /**
     * Check if error is retryable
     */
    private boolean isRetryable(HttpStatus statusCode) {
        return statusCode == HttpStatus.SERVICE_UNAVAILABLE ||
               statusCode == HttpStatus.GATEWAY_TIMEOUT ||
               statusCode == HttpStatus.BAD_GATEWAY ||
               statusCode == HttpStatus.REQUEST_TIMEOUT;
    }

    /**
     * Generate unique request ID for tracing
     */
    private String generateRequestId() {
        return "DISPUTE-" + System.currentTimeMillis() + "-" + System.nanoTime() % 1000;
    }

    /**
     * Response DTO for API integration
     */
    public static class DisputeApiResponse {
        private Long disputeId;
        private String message;
        private String status;
        private String errorCode;
        private String errorDetails;
        private String apiResponse;
        private OffsetDateTime timestamp;
        private boolean success;
        private boolean retryable;

        private DisputeApiResponse() {
            this.timestamp = OffsetDateTime.now();
        }

        // Static factory methods
        public static DisputeApiResponse success(Long disputeId, String message, String apiResponse) {
            DisputeApiResponse response = new DisputeApiResponse();
            response.disputeId = disputeId;
            response.message = message;
            response.status = "SUCCESS";
            response.apiResponse = apiResponse;
            response.success = true;
            response.retryable = false;
            return response;
        }

        public static DisputeApiResponse failure(Long disputeId, String message, String errorCode, String errorDetails) {
            DisputeApiResponse response = new DisputeApiResponse();
            response.disputeId = disputeId;
            response.message = message;
            response.status = "FAILED";
            response.errorCode = errorCode;
            response.errorDetails = errorDetails;
            response.success = false;
            response.retryable = false;
            return response;
        }

        public static DisputeApiResponse retryable(Long disputeId, String message, String errorCode) {
            DisputeApiResponse response = new DisputeApiResponse();
            response.disputeId = disputeId;
            response.message = message;
            response.status = "RETRYABLE";
            response.errorCode = errorCode;
            response.success = false;
            response.retryable = true;
            return response;
        }

        // Getters
        public Long getDisputeId() { return disputeId; }
        public String getMessage() { return message; }
        public String getStatus() { return status; }
        public String getErrorCode() { return errorCode; }
        public String getErrorDetails() { return errorDetails; }
        public String getApiResponse() { return apiResponse; }
        public OffsetDateTime getTimestamp() { return timestamp; }
        public boolean isSuccess() { return success; }
        public boolean isRetryable() { return retryable; }

        @Override
        public String toString() {
            return "DisputeApiResponse{" +
                    "disputeId=" + disputeId +
                    ", status='" + status + '\'' +
                    ", message='" + message + '\'' +
                    ", success=" + success +
                    ", retryable=" + retryable +
                    '}';
        }
    }
}