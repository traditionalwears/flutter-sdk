package com.truecallersdk

import android.app.Activity
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.truecaller.android.sdk.ITrueCallback
import com.truecaller.android.sdk.SdkThemeOptions
import com.truecaller.android.sdk.TrueError
import com.truecaller.android.sdk.TrueException
import com.truecaller.android.sdk.TrueProfile
import com.truecaller.android.sdk.TruecallerSDK
import com.truecaller.android.sdk.TruecallerSdkScope
import com.truecaller.android.sdk.clients.VerificationCallback
import com.truecaller.android.sdk.clients.VerificationDataBundle
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.util.Locale

const val INITIATE_SDK = "initiateSDK"
const val IS_USABLE = "isUsable"
const val SET_DARK_THEME = "setDarkTheme"
const val SET_LOCALE = "setLocale"
const val GET_PROFILE = "getProfile"
const val REQUEST_VERIFICATION = "requestVerification"
const val VERIFY_OTP = "verifyOtp"
const val VERIFY_MISSED_CALL = "verifyMissedCall"
const val TC_METHOD_CHANNEL = "tc_method_channel"
const val TC_EVENT_CHANNEL = "tc_event_channel"

/** TruecallerSdkPlugin */
public class TruecallerSdkPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler, ActivityAware {

    /** The MethodChannel that will the communication between Flutter and native Android
     * This local reference serves to register the plugin with the Flutter Engine and unregister it
     * when the Flutter Engine is detached from the Activity
     **/
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var activity: Activity? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        onAttachedToEngine(flutterPluginBinding.binaryMessenger)
    }

    /**This static function is optional and equivalent to onAttachedToEngine. It supports the old
     * pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
     * plugin registration via this function while apps migrate to use the new Android APIs
     * post-flutter-1.12 via https:flutter.dev/go/android-project-migration.
     * It is encouraged to share logic between onAttachedToEngine and registerWith to keep
     * them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
     * depending on the user's project. onAttachedToEngine or registerWith must both be defined
     * in the same class.
     **/
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val truecallerSdkPlugin = TruecallerSdkPlugin()
            truecallerSdkPlugin.activity = registrar.activity()
            truecallerSdkPlugin.onAttachedToEngine(registrar.messenger())
        }
    }

    private fun onAttachedToEngine(messenger: BinaryMessenger) {
        methodChannel = MethodChannel(messenger, TC_METHOD_CHANNEL)
        methodChannel?.setMethodCallHandler(this)
        eventChannel = EventChannel(messenger, TC_EVENT_CHANNEL)
        eventChannel?.setStreamHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            INITIATE_SDK -> {
                getTrueScope(call)?.let { TruecallerSDK.init(it) } ?: result.error("UNAVAILABLE", "Activity not available.", null)
            }
            IS_USABLE -> {
                result.success(TruecallerSDK.getInstance() != null && TruecallerSDK.getInstance().isUsable)
            }
            SET_DARK_THEME -> {
                TruecallerSDK.getInstance().setTheme(SdkThemeOptions.DARK)
            }
            SET_LOCALE -> {
                call.argument<String>(Constants.LOCALE)?.let {
                    TruecallerSDK.getInstance().setLocale(Locale(it))
                }
            }
            GET_PROFILE -> {
                activity?.let { TruecallerSDK.getInstance().getUserProfile(it as FragmentActivity) } ?: result.error(
                    "UNAVAILABLE",
                    "Activity not available.",
                    null
                )
            }
            REQUEST_VERIFICATION -> {
                val phoneNumber = call.argument<String>(Constants.PH_NO)?.takeUnless(String::isBlank)
                    ?: return result.error("Invalid phone", "Can't be null or empty", null)
                activity?.let {
                    TruecallerSDK.getInstance()
                        .requestVerification("IN", phoneNumber, verificationCallback, it as FragmentActivity)
                }
                    ?: result.error("UNAVAILABLE", "Activity not available.", null)
            }
            VERIFY_OTP -> {
                val firstName = call.argument<String>(Constants.FIRST_NAME)?.takeUnless(String::isBlank)
                    ?: return result.error("Invalid name", "Can't be null or empty", null)
                val lastName = call.argument<String>(Constants.LAST_NAME) ?: ""
                val trueProfile = TrueProfile.Builder(firstName, lastName).build()
                val otp = call.argument<String>(Constants.OTP)?.takeUnless(String::isBlank)
                    ?: return result.error("Invalid otp", "Can't be null or empty", null)
                TruecallerSDK.getInstance().verifyOtp(
                    trueProfile,
                    otp,
                    verificationCallback
                )
            }
            VERIFY_MISSED_CALL -> {
                val firstName = call.argument<String>(Constants.FIRST_NAME)?.takeUnless(String::isBlank)
                    ?: return result.error("Invalid name", "Can't be null or empty", null)
                val lastName = call.argument<String>(Constants.LAST_NAME) ?: ""
                val trueProfile = TrueProfile.Builder(firstName, lastName).build()
                TruecallerSDK.getInstance().verifyMissedCall(
                    trueProfile,
                    verificationCallback
                )
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun getTrueScope(call: MethodCall): TruecallerSdkScope? {
        return activity?.let {
            TruecallerSdkScope.Builder(it, sdkCallback)
                .sdkOptions(call.argument<Int>(Constants.SDK_OPTION) ?: TruecallerSdkScope.SDK_OPTION_WITHOUT_OTP)
                .consentMode(call.argument<Int>(Constants.CONSENT_MODE) ?: TruecallerSdkScope.CONSENT_MODE_BOTTOMSHEET)
                .consentTitleOption(call.argument<Int>(Constants.CONSENT_TITLE) ?: TruecallerSdkScope.SDK_CONSENT_TITLE_GET_STARTED)
                .footerType(call.argument<Int>(Constants.FOOTER_TYPE) ?: TruecallerSdkScope.FOOTER_TYPE_SKIP)
                .loginTextPrefix(call.argument<Int>(Constants.LOGIN_TEXT_PRE) ?: TruecallerSdkScope.LOGIN_TEXT_PREFIX_TO_GET_STARTED)
                .loginTextSuffix(call.argument<Int>(Constants.LOGIN_TEXT_SUF) ?: TruecallerSdkScope.LOGIN_TEXT_SUFFIX_PLEASE_LOGIN)
                .ctaTextPrefix(call.argument<Int>(Constants.CTA_TEXT_PRE) ?: TruecallerSdkScope.CTA_TEXT_PREFIX_USE)
                .privacyPolicyUrl(call.argument<String>(Constants.PP_URL) ?: "")
                .termsOfServiceUrl(call.argument<String>(Constants.TOS_URL) ?: "")
                .buttonShapeOptions(call.argument<Int>(Constants.BTN_SHAPE) ?: TruecallerSdkScope.BUTTON_SHAPE_ROUNDED)
                .buttonColor(call.argument<Long>(Constants.BTN_CLR)?.toInt() ?: 0)
                .buttonTextColor(call.argument<Long>(Constants.BTN_TXT_CLR)?.toInt() ?: 0)
                .build()
        }
    }

    private val sdkCallback: ITrueCallback = object : ITrueCallback {
        override fun onSuccessProfileShared(trueProfile: TrueProfile) {
            eventSink?.success(
                mapOf(
                    Constants.RESULT to Constants.SUCCESS,
                    Constants.DATA to Gson().toJson(trueProfile)
                )
            )
        }

        override fun onFailureProfileShared(trueError: TrueError) {
            eventSink?.success(
                mapOf(
                    Constants.RESULT to Constants.FAILURE,
                    Constants.DATA to Gson().toJson(trueError)
                )
            )
        }

        override fun onVerificationRequired() {
            eventSink?.success(mapOf(Constants.RESULT to Constants.VERIFICATION))
        }
    }

    private val verificationCallback: VerificationCallback = object : VerificationCallback {
        override fun onRequestSuccess(requestCode: Int, bundle: VerificationDataBundle?) {
            val trueProfile = TrueProfile.Builder("shubh", "ag").build()
            when (requestCode) {
                VerificationCallback.TYPE_MISSED_CALL_INITIATED -> {
                    Toast.makeText(
                        activity,
                        "Missed call initiated",
                        Toast.LENGTH_SHORT
                    ).show()
                    eventSink?.success(
                        mapOf(
                            Constants.RESULT to Constants.MISSED_CALL_INITIATED
                        )
                    )
                }
                VerificationCallback.TYPE_MISSED_CALL_RECEIVED -> {
                    Toast.makeText(
                        activity,
                        "Missed call received",
                        Toast.LENGTH_SHORT
                    ).show()
                    eventSink?.success(
                        mapOf(
                            Constants.RESULT to Constants.MISSED_CALL_RECEIVED
                        )
                    )
                }
                VerificationCallback.TYPE_OTP_INITIATED -> {
                    Toast.makeText(
                        activity,
                        "OTP initiated",
                        Toast.LENGTH_SHORT
                    ).show()
                    eventSink?.success(
                        mapOf(
                            Constants.RESULT to Constants.OTP_INITIATED
                        )
                    )
                }
                VerificationCallback.TYPE_OTP_RECEIVED -> {
                    Toast.makeText(
                        activity,
                        "OTP received",
                        Toast.LENGTH_SHORT
                    ).show()
                    eventSink?.success(
                        mapOf(
                            Constants.RESULT to Constants.OTP_RECEIVED,
                            Constants.DATA to bundle?.getString(VerificationDataBundle.KEY_OTP)
                        )
                    )
                }
                VerificationCallback.TYPE_PROFILE_VERIFIED_BEFORE -> {
                    Toast.makeText(
                        activity,
                        "Profile verified for your app before: " + bundle?.profile?.firstName
                                + " and access token: " + bundle?.profile?.accessToken,
                        Toast.LENGTH_SHORT
                    ).show()
                    eventSink?.success(
                        mapOf(
                            Constants.RESULT to Constants.VERIFIED_BEFORE,
                            Constants.DATA to Gson().toJson(bundle?.profile)
                        )
                    )
                }
                else -> {
                    Toast.makeText(
                        activity,
                        "Success: Verified with " + bundle?.getString(VerificationDataBundle.KEY_ACCESS_TOKEN),
                        Toast.LENGTH_SHORT
                    ).show()
                    eventSink?.success(
                        mapOf(
                            Constants.RESULT to Constants.VERIFICATION_COMPLETE,
                            Constants.DATA to bundle?.getString(VerificationDataBundle.KEY_ACCESS_TOKEN)
                        )
                    )
                }
            }
        }

        override fun onRequestFailure(callbackType: Int, trueException: TrueException) {
            eventSink?.success(
                mapOf(
                    Constants.RESULT to Constants.EXCEPTION,
                    Constants.DATA to Gson().toJson(trueException)
                )
            )
        }
    }

    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
        this.eventSink = eventSink
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        activity = null
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        eventSink = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity
        binding.addActivityResultListener { _, resultCode, data ->
            TruecallerSDK.getInstance().onActivityResultObtained(activity as FragmentActivity, resultCode, data)
        }
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.activity = binding.activity
        binding.addActivityResultListener { _, resultCode, data ->
            TruecallerSDK.getInstance().onActivityResultObtained(activity as FragmentActivity, resultCode, data)
        }
    }

    override fun onDetachedFromActivity() {
        this.activity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.activity = null
    }
}
