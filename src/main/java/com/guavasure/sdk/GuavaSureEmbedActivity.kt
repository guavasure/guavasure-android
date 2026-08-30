package com.guavasure.sdk

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.guavasure.sdk.payment.PaymentLauncher
import com.razorpay.PaymentResultListener

/**
 * Optional base activity that forwards Razorpay callbacks to [GuavaSureEmbedView].
 *
 * Razorpay Android SDK 1.6+ delivers results on the host Activity - not via
 * `Checkout.handleActivityResult`. Extend this class (or implement
 * [GuavaSurePaymentHost] on your own activity).
 */
abstract class GuavaSureEmbedActivity : AppCompatActivity(), GuavaSurePaymentHost, PaymentResultListener {
    val paymentLauncher: PaymentLauncher by lazy { PaymentLauncher(this) }
    private var paymentListener: PaymentResultListener? = null

    override fun registerPaymentListener(listener: PaymentResultListener) {
        paymentListener = listener
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        paymentLauncher.onPaymentSuccess(razorpayPaymentId)
        paymentListener?.onPaymentSuccess(razorpayPaymentId)
    }

    override fun onPaymentError(code: Int, description: String?) {
        paymentLauncher.onPaymentError(code, description)
        paymentListener?.onPaymentError(code, description)
    }
}

/**
 * Implement on your activity if you cannot extend [GuavaSureEmbedActivity].
 */
interface GuavaSurePaymentHost {
    fun registerPaymentListener(listener: PaymentResultListener)
}
