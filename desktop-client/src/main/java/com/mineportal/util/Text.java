package com.mineportal.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Renders Adventure chat components to plain text for the Swing log. */
public final class Text {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static String plain(Component component) {
        if (component == null) {
            return "";
        }
        return PLAIN.serialize(component);
    }

    private Text() {
    }
}
