package com.zebchat.chat.internal

import androidx.core.content.FileProvider

/**
 * Shares photos taken from the chat's file chooser with the camera app. A subclass, so it never
 * clashes with a `FileProvider` the host app declares.
 */
public class ZebChatFileProvider : FileProvider()
