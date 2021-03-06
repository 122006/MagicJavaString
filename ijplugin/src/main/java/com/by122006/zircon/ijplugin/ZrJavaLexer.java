package com.by122006.zircon.ijplugin;

import com.intellij.lang.java.lexer.JavaLexer;
import com.intellij.lexer.LexerBase;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.DetailsComponent;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.TokenType;
import com.intellij.psi.impl.source.tree.JavaDocElementType;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.text.CharArrayUtil;
import formatter.Formatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import formatter.ReflectionUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;


@SuppressWarnings({"unchecked", "rawtypes"})
public final class ZrJavaLexer extends LexerBase {
    private static final Logger LOG = Logger.getInstance(ZrJavaLexer.class.getName());

    private Object myFlexLexer;
    private CharSequence myBuffer;
    private @Nullable
    char[] myBufferArray;
    private int myBufferIndex;
    private int myBufferEndOffset;
    private int myTokenEndOffset;  // positioned after the last symbol of the current token
    private IElementType myTokenType;

    public ZrJavaLexer(@NotNull LanguageLevel level) {
        try {
            Constructor<?> constructor = getFlexClazz().getConstructor(LanguageLevel.class);
            constructor.setAccessible(true);
            myFlexLexer = constructor.newInstance(level);
        } catch (Exception e) {
            LOG.error(e);
        }
    }

    private Class getFlexClazz() {
        try {
            return Class.forName("com.intellij.lang.java.lexer._JavaLexer");
        } catch (ClassNotFoundException e) {
            LOG.error(e);
            throw new RuntimeException();
        }
    }

    public ZrJavaLexer(JavaLexer lexer) {
        super();
        myFlexLexer = ReflectionUtil.getDeclaredField(lexer, JavaLexer.class, "myFlexLexer");
    }

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        myBuffer = buffer;
        myBufferArray = CharArrayUtil.fromSequenceWithoutCopying(buffer);
        myBufferIndex = startOffset;
        myBufferEndOffset = endOffset;
        myTokenType = null;
        myTokenEndOffset = startOffset;
        try {
            Method reset = getFlexClazz().getMethod("reset", CharSequence.class, int.class, int.class, int.class);
            reset.setAccessible(true);
            reset.invoke(myFlexLexer, myBuffer, startOffset, endOffset, 0);
        } catch (Exception e) {
            LOG.error(e);
        }
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public IElementType getTokenType() {
        locateToken();
        return myTokenType;
    }

    @Override
    public int getTokenStart() {
        return myBufferIndex;
    }

    @Override
    public int getTokenEnd() {
        locateToken();
        return myTokenEndOffset;
    }

    @Override
    public void advance() {
        locateToken();
        myTokenType = null;
    }

    private void locateToken() {
        if (myTokenType != null) return;

        if (myTokenEndOffset == myBufferEndOffset) {
            myBufferIndex = myBufferEndOffset;
            return;
        }

        myBufferIndex = myTokenEndOffset;

        char c = charAt(myBufferIndex);
        switch (c) {
            case ' ':
            case '\t':
            case '\n':
            case '\r':
            case '\f':
                myTokenType = TokenType.WHITE_SPACE;
                myTokenEndOffset = getWhitespaces(myBufferIndex + 1);
                break;

            case '/':
                if (myBufferIndex + 1 >= myBufferEndOffset) {
                    myTokenType = JavaTokenType.DIV;
                    myTokenEndOffset = myBufferEndOffset;
                } else {
                    char nextChar = charAt(myBufferIndex + 1);
                    if (nextChar == '/') {
                        myTokenType = JavaTokenType.END_OF_LINE_COMMENT;
                        myTokenEndOffset = getLineTerminator(myBufferIndex + 2);
                    } else if (nextChar == '*') {
                        if (myBufferIndex + 2 >= myBufferEndOffset ||
                                (charAt(myBufferIndex + 2)) != '*' ||
                                (myBufferIndex + 3 < myBufferEndOffset &&
                                        (charAt(myBufferIndex + 3)) == '/')) {
                            myTokenType = JavaTokenType.C_STYLE_COMMENT;
                            myTokenEndOffset = getClosingComment(myBufferIndex + 2);
                        } else {
                            myTokenType = JavaDocElementType.DOC_COMMENT;
                            myTokenEndOffset = getClosingComment(myBufferIndex + 3);
                        }
                    } else {
                        flexLocateToken();
                    }
                }
                break;

            case '#':
                if (myBufferIndex == 0 && myBufferIndex + 1 < myBufferEndOffset && charAt(myBufferIndex + 1) == '!') {
                    myTokenType = JavaTokenType.END_OF_LINE_COMMENT;
                    myTokenEndOffset = getLineTerminator(myBufferIndex + 2);
                } else {
                    flexLocateToken();
                }
                break;
            case '\'':
                myTokenType = JavaTokenType.CHARACTER_LITERAL;
                myTokenEndOffset = getClosingQuote(myBufferIndex + 1, c);
                break;

            case '"':
                if (myBufferIndex + 2 < myBufferEndOffset && charAt(myBufferIndex + 2) == '"' && charAt(myBufferIndex + 1) == '"') {
                    myTokenType = JavaTokenType.TEXT_BLOCK_LITERAL;
                    myTokenEndOffset = getTextBlockEnd(myBufferIndex + 2);
                } else {
                    myTokenType = JavaTokenType.STRING_LITERAL;
                    myTokenEndOffset = getClosingQuote(myBufferIndex + 1, c);
                }
                break;
            default: {
                Formatter formatter = Formatter.getAllFormatters().stream().filter((Formatter a) -> {
                    String prefix = a.prefix();
                    int length = prefix.length();
                    if (myBufferIndex + length >= myBuffer.length()) return false;
                    if (charAt(myBufferIndex + length) != '"') return false;
                    for (int i = 0; i < length; i++) {
                        if (charAt(myBufferIndex + i) != prefix.charAt(i)) return false;
                    }
                    return true;
                }).findFirst().orElse(null);
                if (formatter != null) {
                    LOG.info("myBufferEndOffset: " + myBufferEndOffset);
                    myTokenType = JavaTokenType.STRING_LITERAL;
                    myTokenEndOffset = getClosingQuote(myBufferIndex + 1 + formatter.prefix().length(), '"');
                } else {
                    flexLocateToken();
                }
            }
        }

        if (myTokenEndOffset > myBufferEndOffset) {
            myTokenEndOffset = myBufferEndOffset;
        }
    }

    private int getWhitespaces(int offset) {
        if (offset >= myBufferEndOffset) {
            return myBufferEndOffset;
        }

        int pos = offset;
        char c = charAt(pos);

        while (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
            pos++;
            if (pos == myBufferEndOffset) return pos;
            c = charAt(pos);
        }

        return pos;
    }

    private static Method goTo = null;
    private static Method advance = null;
    private static Method getTokenEnd = null;

    private void flexLocateToken() {
        try {
            if (goTo == null) {
                goTo = getFlexClazz().getMethod("goTo", int.class);
                goTo.setAccessible(true);
            }
            goTo.invoke(myFlexLexer, myBufferIndex);
            if (advance == null) {
                advance = getFlexClazz().getMethod("advance");
                advance.setAccessible(true);
            }
            myTokenType = (IElementType) advance.invoke(myFlexLexer);
            if (getTokenEnd == null) {
                getTokenEnd = getFlexClazz().getMethod("getTokenEnd");
                getTokenEnd.setAccessible(true);
            }
            myTokenEndOffset = (int) getTokenEnd.invoke(myFlexLexer);
        } catch (Exception e) {
            LOG.error(e);
        }
    }

    private int getClosingQuote(int offset, char quoteChar) {
        if (offset >= myBufferEndOffset) {
            return myBufferEndOffset;
        }

        int pos = offset;
        char c = charAt(pos);

        while (true) {
            while (c != quoteChar && c != '\n' && c != '\r' && c != '\\') {
                pos++;
                if (pos >= myBufferEndOffset) return myBufferEndOffset;
                c = charAt(pos);
            }

            if (c == '\\') {
                pos++;
                if (pos >= myBufferEndOffset) return myBufferEndOffset;
                c = charAt(pos);
                if (c == '\n' || c == '\r') continue;
                if (c == 'u') {
                    do {
                        pos++;
                    }
                    while (pos < myBufferEndOffset && charAt(pos) == 'u');
                    if (pos + 3 >= myBufferEndOffset) return myBufferEndOffset;
                    boolean isBackSlash = charAt(pos) == '0' && charAt(pos + 1) == '0' && charAt(pos + 2) == '5' && charAt(pos + 3) == 'c';
                    // on encoded backslash we also need to skip escaped symbol (e.g. \\u005c" is translated to \")
                    pos += (isBackSlash ? 5 : 4);
                } else {
                    pos++;
                }
                if (pos >= myBufferEndOffset) return myBufferEndOffset;
                c = charAt(pos);
            } else if (c == quoteChar) {
                break;
            } else {
                pos--;
                break;
            }
        }

        return pos + 1;
    }

    private int getClosingComment(int offset) {
        int pos = offset;

        while (pos < myBufferEndOffset - 1) {
            char c = charAt(pos);
            if (c == '*' && (charAt(pos + 1)) == '/') {
                break;
            }
            pos++;
        }

        return pos + 2;
    }

    private int getLineTerminator(int offset) {
        int pos = offset;

        while (pos < myBufferEndOffset) {
            char c = charAt(pos);
            if (c == '\r' || c == '\n') break;
            pos++;
        }

        return pos;
    }

    private int getTextBlockEnd(int offset) {
        int pos = offset;

        while ((pos = getClosingQuote(pos + 1, '"')) < myBufferEndOffset) {
            char current = charAt(pos);
            if (current == '\\') {
                pos++;
            } else if (current == '"' && pos + 1 < myBufferEndOffset && charAt(pos + 1) == '"') {
                pos += 2;
                break;
            }
        }

        return pos;
    }

    private char charAt(int position) {
        return myBufferArray != null ? myBufferArray[position] : myBuffer.charAt(position);
    }

    @NotNull
    @Override
    public CharSequence getBufferSequence() {
        return myBuffer;
    }

    @Override
    public int getBufferEnd() {
        return myBufferEndOffset;
    }
}