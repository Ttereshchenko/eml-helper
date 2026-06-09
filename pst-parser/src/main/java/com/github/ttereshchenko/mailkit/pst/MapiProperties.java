package com.github.ttereshchenko.mailkit.pst;

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
     * Attachment flags.
     * EML alternative: Influences the Content-Disposition (e.g., inline).
     */
    public static final int PR_ATTACH_FLAGS = 0x3714;

    // Other / Table Properties

    /**
     * Row ID for tables (e.g., attachment tables, hierarchy tables).
     */
    public static final int PidTagLtpRowId = 0x67F2;

    /**
     * Spam Confidence Level
     */
    public static final int PR_CONTENT_FILTER_SPAM_CONFIDENCE_LEVEL = 0x4076;

    private MapiProperties() {}
}
