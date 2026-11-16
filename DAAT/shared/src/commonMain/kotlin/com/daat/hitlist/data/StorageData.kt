package com.daat.hitlist.data

import dev.gitlive.firebase.storage.Data

/** Wraps JPEG bytes in the platform's upload type. */
internal expect fun ByteArray.toStorageData(): Data
