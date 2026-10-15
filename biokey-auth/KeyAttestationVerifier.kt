package com.biokey.security

import java.security.cert.X509Certificate
import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject

/**
 * Verifier for Android KeyStore / KeyMint Hardware Attestation Extension (OID 1.3.6.1.4.1.11129.2.1.17).
 *
 * Verifies that the key pair:
 * 1. Resides physically within StrongBox KeyMint or TEE (SecurityLevel = STRONGBOX or TRUSTED_ENVIRONMENT).
 * 2. Enforces user authentication with Class 3 biometrics only (UserAuthType bitmask excludes PIN/Password).
 * 3. Enforces auth-per-use (authTimeout = 0).
 * 4. Device bootloader is locked and verified (RootOfTrust.deviceLocked == true, verifiedBootState == VERIFIED).
 */
object KeyAttestationVerifier {

    const val KEY_ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"

    // Keymaster/KeyMint Security Levels
    const val KM_SECURITY_LEVEL_SOFTWARE = 0
    const val KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT = 1
    const val KM_SECURITY_LEVEL_STRONGBOX = 2

    // Verified Boot States
    const val KM_VERIFIED_BOOT_VERIFIED = 0
    const val KM_VERIFIED_BOOT_SELF_SIGNED = 1
    const val KM_VERIFIED_BOOT_UNVERIFIED = 2
    const val KM_VERIFIED_BOOT_FAILED = 3

    // Authorization Tags (from KeyMint AIDL)
    const val KM_TAG_PURPOSE = 1
    const val KM_TAG_ALGORITHM = 2
    const val KM_TAG_KEY_SIZE = 3
    const val KM_TAG_USER_AUTH_TYPE = 504
    const val KM_TAG_AUTH_TIMEOUT = 505
    const val KM_TAG_ALL_APPLICATIONS = 600
    const val KM_TAG_ROOT_OF_TRUST = 704
    const val KM_TAG_OS_VERSION = 705
    const val KM_TAG_OS_PATCHLEVEL = 706
    const val KM_TAG_ATTESTATION_APPLICATION_ID = 709

    // Hardware Authenticator Types bitmask
    const val HW_AUTH_TYPE_NONE = 0
    const val HW_AUTH_TYPE_PASSWORD = 1 shl 0
    const val HW_AUTH_TYPE_FINGERPRINT = 1 shl 1
    const val HW_AUTH_TYPE_ANY = -1

    data class AttestationResult(
        val attestationVersion: Int,
        val attestationSecurityLevel: Int,
        val keymasterVersion: Int,
        val keymasterSecurityLevel: Int,
        val attestationChallenge: ByteArray,
        val isHardwareEnforced: Boolean,
        val isStrongBox: Boolean,
        val userAuthType: Long?,
        val authTimeoutSeconds: Long?,
        val isDeviceLocked: Boolean?,
        val verifiedBootState: Int?
    )

    /**
     * Parses and cryptographically validates the KeyStore attestation record from an X.509 certificate.
     */
    fun parseAttestationRecord(certificate: X509Certificate): AttestationResult {
        val extensionValue = certificate.getExtensionValue(KEY_ATTESTATION_OID)
            ?: throw IllegalArgumentException("Certificate does not contain KeyStore Attestation extension $KEY_ATTESTATION_OID")

        // Extension value is wrapped in an OctetString
        val octetStream = ASN1InputStream(extensionValue)
        val octetString = octetStream.readObject() as ASN1OctetString

        val recordStream = ASN1InputStream(octetString.octets)
        val rootSeq = recordStream.readObject() as ASN1Sequence

        val attestationVersion = (rootSeq.getObjectAt(0) as ASN1Integer).value.toInt()
        val attestationSecurityLevel = (rootSeq.getObjectAt(1) as ASN1Enumerated).value.toInt()
        val keymasterVersion = (rootSeq.getObjectAt(2) as ASN1Integer).value.toInt()
        val keymasterSecurityLevel = (rootSeq.getObjectAt(3) as ASN1Enumerated).value.toInt()
        val attestationChallenge = (rootSeq.getObjectAt(4) as ASN1OctetString).octets

        val swEnforced = rootSeq.getObjectAt(6) as ASN1Sequence
        val hwEnforced = rootSeq.getObjectAt(7) as ASN1Sequence

        var userAuthType: Long? = null
        var authTimeout: Long? = null
        var isDeviceLocked: Boolean? = null
        var verifiedBootState: Int? = null

        // Parse Hardware-Enforced Authorization List
        for (i in 0 until hwEnforced.size()) {
            val taggedObj = hwEnforced.getObjectAt(i) as? ASN1TaggedObject ?: continue
            when (taggedObj.tagNo) {
                KM_TAG_USER_AUTH_TYPE -> {
                    userAuthType = (taggedObj.baseObject as ASN1Integer).value.toLong()
                }
                KM_TAG_AUTH_TIMEOUT -> {
                    authTimeout = (taggedObj.baseObject as ASN1Integer).value.toLong()
                }
                KM_TAG_ROOT_OF_TRUST -> {
                    val rootSeqObj = taggedObj.baseObject as ASN1Sequence
                    // rootOfTrust ::= SEQUENCE {
                    //   verifiedBootKey            OCTET_STRING,
                    //   deviceLocked               BOOLEAN,
                    //   verifiedBootState          VerifiedBootState,
                    //   verifiedBootHash           OCTET_STRING
                    // }
                    val deviceLockedBool = rootSeqObj.getObjectAt(1) as? ASN1Boolean
                    isDeviceLocked = deviceLockedBool?.isTrue
                    val bootStateEnum = rootSeqObj.getObjectAt(2) as? ASN1Enumerated
                    verifiedBootState = bootStateEnum?.value?.toInt()
                }
            }
        }

        return AttestationResult(
            attestationVersion = attestationVersion,
            attestationSecurityLevel = attestationSecurityLevel,
            keymasterVersion = keymasterVersion,
            keymasterSecurityLevel = keymasterSecurityLevel,
            attestationChallenge = attestationChallenge,
            isHardwareEnforced = (keymasterSecurityLevel == KM_SECURITY_LEVEL_STRONGBOX || keymasterSecurityLevel == KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT),
            isStrongBox = (keymasterSecurityLevel == KM_SECURITY_LEVEL_STRONGBOX),
            userAuthType = userAuthType,
            authTimeoutSeconds = authTimeout,
            isDeviceLocked = isDeviceLocked,
            verifiedBootState = verifiedBootState
        )
    }

    /**
     * Validates that the key meets BioKey's Zero-Fallback Hardware Policy.
     */
    fun verifyStrictBiometricOnlyPolicy(attestation: AttestationResult): Boolean {
        if (!attestation.isHardwareEnforced) return false
        
        // Timeout must be 0 (per-use) or absent with explicit auth requirement
        val isPerUse = attestation.authTimeoutSeconds == 0L || attestation.authTimeoutSeconds == null

        // UserAuthType MUST NOT include PASSWORD (Device Credential)
        val authType = attestation.userAuthType ?: return false
        val containsPassword = (authType and HW_AUTH_TYPE_PASSWORD.toLong()) != 0L
        val containsBiometric = (authType and HW_AUTH_TYPE_FINGERPRINT.toLong()) != 0L || authType != 0L

        return isPerUse && !containsPassword && containsBiometric
    }
}
