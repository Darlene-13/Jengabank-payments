package com.jengabank.payments.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of a transfer reject. The reason is stored on the transfer and in the audit trail. */
public record RejectTransferRequest(@NotBlank @Size(min = 5, max = 280) String reason) {}
