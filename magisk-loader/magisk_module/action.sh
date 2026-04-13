#!/system/bin/sh

am broadcast --receiver-foreground -a android.provider.Telephony.SECRET_CODE -d android_secret_code://5776733

exit 0