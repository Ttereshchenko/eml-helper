package com.github.ttereshchenko.mailkit.pst;

/**
 * MAPI property-tag constants used by the parser, preferring the unicode ({@code _W}) variants. Each
 * constant documents the closest EML / RFC&nbsp;5322 header equivalent where one exists.
 */
public final class MapiProperties {

    // Message Properties

    /**
     * The subject of the message.
     * EML alternative: The "Subject:" header.
     */
    public static final int PR_SUBJECT_W = 0x0037;

    /**
     * The message class (e.g., IPM.Note).
     * EML alternative: Usually maps conceptually to the "Content-Type"
     * but has no direct 1-to-1 standard header mapping.
     */
    public static final int PR_MESSAGE_CLASS_W = 0x001A;

    /**
     * The delivery time of the message.
     * EML alternative: The "Date:" header.
     */
    public static final int PR_MESSAGE_DELIVERY_TIME = 0x0E06;

    /**
     * The client submit time of the message.
     * EML alternative: Used as fallback for the "Date:" header.
     */
    public static final int PR_CLIENT_SUBMIT_TIME = 0x0039;

    /**
     * The internet message ID.
     * EML alternative: The "Message-ID:" header.
     */
    public static final int PR_INTERNET_MESSAGE_ID_W = 0x1035;

    /**
     * The Message-ID of the message this one replies to.
     * EML alternative: The "In-Reply-To:" header.
     */
    public static final int PR_IN_REPLY_TO_ID_W = 0x1042;

    /**
     * The thread of Message-IDs this message belongs to.
     * EML alternative: The "References:" header.
     */
    public static final int PR_INTERNET_REFERENCES_W = 0x1039;

    /**
     * The sender-assigned importance ({@code 0} = low, {@code 1} = normal, {@code 2} = high).
     * EML alternative: The "Importance:" / "X-Priority:" headers.
     */
    public static final int PR_IMPORTANCE = 0x0017;

    /**
     * The sender-assigned sensitivity ({@code 0} = normal, {@code 1} = personal, {@code 2} =
     * private, {@code 3} = company-confidential).
     * EML alternative: The "Sensitivity:" header.
     */
    public static final int PR_SENSITIVITY = 0x0036;

    /**
     * The normalized subject shared by all messages of a conversation thread.
     * EML alternative: The "Thread-Topic:" header.
     */
    public static final int PR_CONVERSATION_TOPIC_W = 0x0070;

    /**
     * The binary conversation index tracking the message's position in its thread.
     * EML alternative: The "Thread-Index:" header (base64-encoded).
     */
    public static final int PR_CONVERSATION_INDEX = 0x0071;

    /**
     * The reply recipients as a FLATENTRYLIST of ENTRYIDs ([MS-OXCDATA] §2.3.3).
     * EML alternative: The "Reply-To:" header.
     */
    public static final int PR_REPLY_RECIPIENT_ENTRIES = 0x004F;

    /**
     * The reply recipients' display names, semicolon-separated.
     * EML alternative: The display-name portions of the "Reply-To:" header.
     */
    public static final int PR_REPLY_RECIPIENT_NAMES_W = 0x0050;

    /**
     * The plain text body of the message.
     * EML alternative: The text/plain part of the message body.
     */
    public static final int PR_BODY_W = 0x1000;

    /**
     * The HTML body of the message.
     * EML alternative: The text/html part of the message body.
     */
    public static final int PR_HTML = 0x1013;

    /**
     * The compressed RTF body of the message.
     * EML alternative: A text/rtf part, though often converted.
     */
    public static final int PR_RTF_COMPRESSED = 0x1009;

    /**
     * The original transport headers.
     * EML alternative: The block of MIME headers at the start of the file.
     */
    public static final int PR_TRANSPORT_MESSAGE_HEADERS_W = 0x007D;

    /**
     * The codepage/charset of the message.
     * EML alternative: The "charset=" parameter in the Content-Type header.
     */
    public static final int PR_INTERNET_CPID = 0x3FDE;

    /**
     * The codepage of the message.
     * EML alternative: Used as fallback for PR_INTERNET_CPID.
     */
    public static final int PR_MESSAGE_CODEPAGE = 0x3FFD;

    /**
     * The store-wide default code page, kept on the message store object (PidTagCodePageId).
     * Not documented in [MS-PST]; the id matches libpff's message-store codepage entry. Used as the
     * last-resort PT_STRING8 code page for messages that carry no code page of their own.
     */
    public static final int PR_CODE_PAGE_ID = 0x66C3;

    /**
     * Has attachment flag.
     */
    public static final int PR_HASATTACH = 0x0E1B;

    // Sender/Recipient Properties

    /**
     * The display name of the sender or recipient.
     * EML alternative: The display name portion of "From:", "To:", "Cc:", or "Bcc:" headers.
     */
    public static final int PR_DISPLAY_NAME_W = 0x3001;

    /**
     * The address type of the recipient (e.g., EX or SMTP).
     * EML alternative: Implicitly SMTP in standard emails.
     */
    public static final int PR_ADDRTYPE = 0x3002;

    /**
     * The native email address of the recipient (can be SMTP or Legacy Exchange DN).
     * EML alternative: The email portion of "To:", "Cc:", or "Bcc:" headers.
     */
    public static final int PR_EMAIL_ADDRESS_W = 0x3003;

    /**
     * The native email address of the sender (can be SMTP or Legacy Exchange DN).
     * EML alternative: The email portion of the "From:" header.
     */
    public static final int PR_SENDER_EMAIL_ADDRESS_W = 0x0C1F;

    /**
     * The display name of the sender.
     * EML alternative: The name portion of the "From:" header.
     */
    public static final int PR_SENDER_NAME_W = 0x0C1A;

    /**
     * The address type of the sender (e.g., EX or SMTP).
     * EML alternative: Implicitly SMTP in standard emails.
     */
    public static final int PR_SENDER_ADDRTYPE_W = 0x0C1E;

    /**
     * The name of the person who sent the email on behalf of someone else.
     * EML alternative: The "Sender:" header.
     */
    public static final int PR_SENT_REPRESENTING_NAME_W = 0x0042;

    /**
     * The native email address of the person who sent the email on behalf of someone else.
     * EML alternative: The email portion of the "Sender:" header.
     */
    public static final int PR_SENT_REPRESENTING_EMAIL_ADDRESS_W = 0x0065;

    /**
     * The address type of the person who sent the email on behalf of someone else.
     * EML alternative: Implicitly SMTP in standard emails.
     */
    public static final int PR_SENT_REPRESENTING_ADDRTYPE_W = 0x0064;

    /**
     * The explicitly cached SMTP address of the recipient.
     * EML alternative: The email portion of "To:", "Cc:", or "Bcc:" headers.
     */
    public static final int PR_SMTP_ADDRESS_W = 0x39FE;

    /**
     * The explicitly cached SMTP address of the sender.
     * EML alternative: The email portion of the "From:" header.
     */
    public static final int PR_SENDER_SMTP_ADDRESS_W = 0x5D01;

    /**
     * The explicitly cached SMTP address of the person who sent the email on behalf of someone else.
     * EML alternative: The email portion of the "Sender:" header.
     */
    public static final int PR_SENT_REPRESENTING_SMTP_ADDRESS_W = 0x5D02;

    /**
     * The display string for all To recipients.
     * EML alternative: Used for fallback.
     */
    public static final int PR_DISPLAY_TO_W = 0x0E04;

    /**
     * The display string for all Cc recipients.
     * EML alternative: Used as a fallback for the "Cc:" header.
     */
    public static final int PR_DISPLAY_CC_W = 0x0E03;

    /**
     * The display string for all Bcc recipients.
     * EML alternative: Used as a fallback for the "Bcc:" header.
     */
    public static final int PR_DISPLAY_BCC_W = 0x0E02;

    /**
     * The recipient type (e.g., To, Cc, Bcc).
     * EML alternative: Determines whether it maps to "To:", "Cc:", or "Bcc:".
     */
    public static final int PR_RECIPIENT_TYPE = 0x0C15;

    // Attachment Properties

    /**
     * The long filename of the attachment.
     * EML alternative: The "filename" parameter in the "Content-Disposition" header.
     */
    public static final int PR_ATTACH_LONG_FILENAME_W = 0x3707;

    /**
     * The short filename of the attachment.
     * EML alternative: The "filename" parameter in the "Content-Disposition" header.
     */
    public static final int PR_ATTACH_FILENAME_W = 0x3704;

    /**
     * The MIME tag (content type) of the attachment.
     * EML alternative: The "Content-Type" header of the attachment part.
     */
    public static final int PR_ATTACH_MIME_TAG_W = 0x370E;

    /**
     * The binary payload data of the attachment.
     * EML alternative: The base64-encoded body of the MIME attachment part.
     */
    public static final int PR_ATTACH_DATA_BIN = 0x3701;

    /**
     * The attachment method (e.g., embedded message, embedded file).
     * EML alternative: Influences the overall MIME structure.
     */
    public static final int PR_ATTACH_METHOD = 0x3705;

    /**
     * The Content-ID of the attachment for inline elements.
     * EML alternative: The "Content-ID:" header.
     */
    public static final int PR_ATTACH_CONTENT_ID_W = 0x3712;

    /**
     * The disposition of the attachment (e.g., attachment, inline).
     * EML alternative: The "Content-Disposition:" header.
     */
    public static final int PR_ATTACH_DISPOSITION = 0x3716;

    /**
     * The Content-Location URI of the attachment (MHTML web archives).
     * EML alternative: The "Content-Location:" header.
     */
    public static final int PR_ATTACH_CONTENT_LOCATION_W = 0x3713;

    /**
     * The approximate size in bytes of the attachment object, content plus property overhead
     * (PidTagAttachSize). Used to tell a too-large attachment apart from one with no stored content.
     * EML alternative: none.
     */
    public static final int PR_ATTACH_SIZE = 0x0E20;

    /**
     * Attachment flags ({@code ATT_INVISIBLE_IN_HTML} = 0x1, {@code ATT_INVISIBLE_IN_RTF} = 0x2,
     * {@code ATT_MHTML_REF} = 0x4).
     * EML alternative: Influences the Content-Disposition (e.g., inline).
     */
    public static final int PR_ATTACH_FLAGS = 0x3714;

    /**
     * Whether the attachment is hidden from the attachment list (PidTagAttachmentHidden), as inline
     * cid-referenced images typically are.
     * EML alternative: Influences the Content-Disposition (inline vs. attachment).
     */
    public static final int PR_ATTACHMENT_HIDDEN = 0x7FFE;

    // Other / Table Properties

    /**
     * Row ID for tables (e.g., attachment tables, hierarchy tables).
     */
    public static final int PidTagLtpRowId = 0x67F2;

    /**
     * Spam Confidence Level
     */
    public static final int PR_CONTENT_FILTER_SPAM_CONFIDENCE_LEVEL = 0x4076;

    /**
     * The CRC of the Outlook password on the message store object (PidTagPstPassword). The content
     * is not encrypted with the password; see {@link PstFile#isPasswordProtected()}.
     * EML alternative: none.
     */
    public static final int PR_PST_PASSWORD = 0x67FF;

    // Contact Properties ([MS-OXOCNTC]) — used for vCard export of IPM.Contact items.

    /** The contact's given (first) name (PidTagGivenName). EML alternative: vCard N. */
    public static final int PR_GIVEN_NAME_W = 0x3A06;

    /** The contact's surname (PidTagSurname). EML alternative: vCard N. */
    public static final int PR_SURNAME_W = 0x3A11;

    /** The contact's company (PidTagCompanyName). EML alternative: vCard ORG. */
    public static final int PR_COMPANY_NAME_W = 0x3A16;

    /** The contact's job title (PidTagTitle). EML alternative: vCard TITLE. */
    public static final int PR_TITLE_W = 0x3A17;

    /** The contact's business phone (PidTagBusinessTelephoneNumber). EML alternative: vCard TEL;TYPE=work. */
    public static final int PR_BUSINESS_TELEPHONE_NUMBER_W = 0x3A08;

    /** The contact's home phone (PidTagHomeTelephoneNumber). EML alternative: vCard TEL;TYPE=home. */
    public static final int PR_HOME_TELEPHONE_NUMBER_W = 0x3A09;

    /** The contact's mobile phone (PidTagMobileTelephoneNumber). EML alternative: vCard TEL;TYPE=cell. */
    public static final int PR_MOBILE_TELEPHONE_NUMBER_W = 0x3A1C;

    private MapiProperties() {}
}
