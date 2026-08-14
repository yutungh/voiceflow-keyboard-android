package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PhraseReplacement {
    final String from;
    final String to;
    final String context;

    PhraseReplacement(String from, String to) {
        this(from, to, "");
    }

    PhraseReplacement(String from, String to, String context) {
        this.from = from == null ? "" : from;
        this.to = to == null ? "" : to;
        this.context = context == null ? "" : context;
    }

    List<String> heardForms() {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : from.split("\\r?\\n")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }
}
