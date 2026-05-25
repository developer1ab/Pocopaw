package com.atombits.pocopaw

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal const val SYSTEM_INTENT_DIAL = "system.intent.dial"
internal const val SYSTEM_INTENT_CALL = "system.intent.call"

private val directPhoneInputPattern = Regex("^[+*#0-9][0-9\\s\\-()*,;#]*$")

internal data class PhoneContactLookupOutcome(
    val phoneNumbers: List<String> = emptyList(),
    val permissionMissing: Boolean = false
)

internal interface PhoneContactResolver {
    fun lookupPhoneNumbers(contactName: String): PhoneContactLookupOutcome
}

internal object NoOpPhoneContactResolver : PhoneContactResolver {
    override fun lookupPhoneNumbers(contactName: String): PhoneContactLookupOutcome = PhoneContactLookupOutcome()
}

internal data class PhoneContactMatchRow(
    val rawContactId: Long?,
    val normalizedNumber: String?,
    val number: String?
)

internal class AndroidPhoneContactResolver(
    private val context: Context
) : PhoneContactResolver {

    override fun lookupPhoneNumbers(contactName: String): PhoneContactLookupOutcome {
        val normalizedContactName = contactName.trim()
        if (normalizedContactName.isBlank()) {
            return PhoneContactLookupOutcome()
        }
        if (!hasReadContactsPermission(context)) {
            return PhoneContactLookupOutcome(permissionMissing = true)
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = listOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_ALTERNATIVE,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        ).joinToString(separator = " OR ") { column -> "$column = ? COLLATE NOCASE" }
        val selectionArgs = arrayOf(normalizedContactName, normalizedContactName, normalizedContactName)
        val phoneRows = mutableListOf<PhoneContactMatchRow>()

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val rawContactIdIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            val normalizedNumberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                phoneRows.add(
                    PhoneContactMatchRow(
                        rawContactId = cursor.getLongOrNull(rawContactIdIndex),
                        normalizedNumber = cursor.getTrimmedStringOrNull(normalizedNumberIndex),
                        number = cursor.getTrimmedStringOrNull(numberIndex)
                    )
                )
            }
        }

        val activeRawContactIds = resolveActiveRawContactIds(phoneRows.mapNotNull(PhoneContactMatchRow::rawContactId).toSet())

        return PhoneContactLookupOutcome(
            phoneNumbers = resolveLivePhoneNumbers(phoneRows, activeRawContactIds)
        )
    }

    private fun resolveActiveRawContactIds(rawContactIds: Set<Long>): Set<Long> {
        if (rawContactIds.isEmpty()) {
            return emptySet()
        }

        val projection = arrayOf(ContactsContract.RawContacts._ID)
        val placeholders = rawContactIds.joinToString(separator = ",") { "?" }
        val selection = buildString {
            append(ContactsContract.RawContacts._ID)
            append(" IN (")
            append(placeholders)
            append(") AND ")
            append(ContactsContract.RawContacts.DELETED)
            append(" = 0")
        }
        val selectionArgs = rawContactIds.map(Long::toString).toTypedArray()
        val activeRawContactIds = linkedSetOf<Long>()

        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val rawContactIdIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
            while (cursor.moveToNext()) {
                cursor.getLongOrNull(rawContactIdIndex)?.let(activeRawContactIds::add)
            }
        }

        return activeRawContactIds
    }
}

internal fun resolveLivePhoneNumbers(
    phoneRows: List<PhoneContactMatchRow>,
    activeRawContactIds: Set<Long>
): List<String> {
    val livePhoneNumbers = linkedSetOf<String>()
    phoneRows.asSequence()
        .filter { row -> row.rawContactId == null || row.rawContactId in activeRawContactIds }
        .mapNotNull(::resolveCanonicalPhoneNumber)
        .forEach { phoneNumber ->
            livePhoneNumbers.add(phoneNumber)
        }
    return livePhoneNumbers.toList()
}

private fun resolveCanonicalPhoneNumber(row: PhoneContactMatchRow): String? {
    return sequenceOf(row.normalizedNumber, row.number)
        .mapNotNull { candidate ->
            candidate?.let(::normalizeDirectPhoneNumber)
        }
        .firstOrNull()
}

internal data class SystemIntentPhoneRecipientResolution(
    val inputRecipient: String?,
    val resolvedPhoneNumber: String? = null,
    val failureSummary: String? = null
)

internal fun TaskExecutionBoundaryPacket.resolveCommunicationRecipient(): String? {
    return sequenceOf(
        resolvedSlots["communication.recipient"],
        structuredDetailSlots.domain["recipient"],
        targetLabel?.takeIf { targetType == TargetType.CONTACT },
        targetKey.takeIf { targetType == TargetType.CONTACT }
    ).mapNotNull { value ->
        value?.trim()?.takeIf { candidate -> candidate.isNotBlank() }
    }.firstOrNull()
}

internal fun resolveSystemIntentPhoneRecipient(
    capabilityId: String?,
    boundaryPacket: TaskExecutionBoundaryPacket?,
    contactResolver: PhoneContactResolver = NoOpPhoneContactResolver
): SystemIntentPhoneRecipientResolution? {
    val normalizedCapabilityId = capabilityId?.trim().orEmpty()
    if (normalizedCapabilityId !in setOf(SYSTEM_INTENT_SENDTO_SMS, SYSTEM_INTENT_DIAL, SYSTEM_INTENT_CALL)) {
        return null
    }
    val packet = boundaryPacket ?: return null
    val recipient = packet.resolveCommunicationRecipient()
    val surfaceLabel = when (normalizedCapabilityId) {
        SYSTEM_INTENT_SENDTO_SMS -> UiStrings.resolve(R.string.phone_surface_sms, "SMS")
        else -> UiStrings.resolve(R.string.phone_surface_phone, "Phone")
    }
    if (recipient.isNullOrBlank()) {
        return SystemIntentPhoneRecipientResolution(
            inputRecipient = null,
            failureSummary = UiStrings.resolve(
                R.string.phone_resolution_missing_recipient,
                "%1\$s system intent requires a grounded recipient before launch.",
                surfaceLabel
            )
        )
    }

    normalizeDirectPhoneNumber(recipient)?.let { phoneNumber ->
        return SystemIntentPhoneRecipientResolution(
            inputRecipient = recipient,
            resolvedPhoneNumber = phoneNumber
        )
    }

    val lookupOutcome = contactResolver.lookupPhoneNumbers(recipient)
    if (lookupOutcome.permissionMissing) {
        return SystemIntentPhoneRecipientResolution(
            inputRecipient = recipient,
            failureSummary = UiStrings.resolve(
                R.string.phone_resolution_permission_missing,
                "%1\$s system intent needs Contacts permission to resolve named recipients before launch. Grant contact access or provide a phone number.",
                surfaceLabel
            )
        )
    }

    val resolvedMatches = lookupOutcome.phoneNumbers
        .mapNotNull(::normalizeDirectPhoneNumber)
        .distinct()
    return when (resolvedMatches.size) {
        1 -> SystemIntentPhoneRecipientResolution(
            inputRecipient = recipient,
            resolvedPhoneNumber = resolvedMatches.single()
        )

        0 -> SystemIntentPhoneRecipientResolution(
            inputRecipient = recipient,
            failureSummary = UiStrings.resolve(
                R.string.phone_resolution_no_unique,
                "%1\$s system intent could not resolve a unique phone number for \"%2\$s\". Choose a contact or provide a phone number.",
                surfaceLabel,
                recipient
            )
        )

        else -> SystemIntentPhoneRecipientResolution(
            inputRecipient = recipient,
            failureSummary = UiStrings.resolve(
                R.string.phone_resolution_multiple,
                "%1\$s system intent found multiple phone numbers for \"%2\$s\". Choose a specific contact or provide a phone number.",
                surfaceLabel,
                recipient
            )
        )
    }
}

internal fun normalizeDirectPhoneNumber(value: String): String? {
    val trimmedValue = value.trim()
    if (trimmedValue.isBlank() || !directPhoneInputPattern.matches(trimmedValue)) {
        return null
    }

    val normalizedValue = buildString(trimmedValue.length) {
        trimmedValue.forEach { character ->
            if (character.isDigit() || character == '+' || character == '*' || character == '#' || character == ',' || character == ';') {
                append(character)
            }
        }
    }
    return normalizedValue.takeIf { candidate -> candidate.any(Char::isDigit) }
}

private fun hasReadContactsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Cursor.getLongOrNull(columnIndex: Int): Long? {
    if (columnIndex < 0 || isNull(columnIndex)) {
        return null
    }
    return getLong(columnIndex)
}

private fun Cursor.getTrimmedStringOrNull(columnIndex: Int): String? {
    if (columnIndex < 0) {
        return null
    }
    return getString(columnIndex)
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }
}