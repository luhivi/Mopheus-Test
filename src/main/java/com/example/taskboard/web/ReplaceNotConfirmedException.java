package com.example.taskboard.web;

/**
 * Raised when {@code mode:"replace"} arrives without {@code confirm:"replace"}.
 *
 * <p>Replace is the one import mode that destroys data. Requiring an explicit echo of the word
 * keeps a mis-selected radio button or a replayed request from wiping the board, and gives the
 * client a code it can show instead of a generic validation failure.
 */
public class ReplaceNotConfirmedException extends RuntimeException {

    public ReplaceNotConfirmedException() {
        super("replace mode requires confirm:\"replace\"");
    }
}