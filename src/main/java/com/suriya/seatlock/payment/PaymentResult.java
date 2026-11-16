package com.suriya.seatlock.payment;

public record PaymentResult(PaymentResponse payment, boolean replayed) {
}
