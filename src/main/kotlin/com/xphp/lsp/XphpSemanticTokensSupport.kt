package com.xphp.lsp

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.jetbrains.php.lang.highlighter.PhpHighlightingData as Php

/**
 * Maps the xphp LSP server's semantic-token legend onto PhpStorm's OWN PHP
 * highlighting keys ([com.jetbrains.php.lang.highlighter.PhpHighlightingData]).
 *
 * `.xphp` highlighting arrives entirely as LSP semantic tokens (there is no
 * `XphpLanguage`/parser; parsing is delegated to the LSP), painted by the
 * platform's LSP integration via the [TextAttributesKey] this returns per
 * token.  We point each token at the matching PHP key so xphp code is colored
 * EXACTLY like PHP in whatever editor color scheme is active -- the stock
 * Default/Darcula, the New UI Light/Dark, the 2026.1 "Islands" themes, or any
 * scheme the user has customized.  Tweak PHP's colors under Settings -> Editor
 * -> Color Scheme -> PHP and xphp follows automatically, for free.
 *
 * This replaced an earlier approach that defined custom `XPHP_*` keys and
 * seeded their colors via `<additionalTextAttributes>` for the schemes named
 * "Default" and "Darcula".  That regressed to black on any other scheme
 * (the seed attaches by exact scheme name), and -- by design -- never matched
 * the user's PHP colors.  Deferring to the PHP keys is both more robust and
 * what the user actually wants.
 *
 * Why PHP keys carry color where the platform defaults don't: PhpStorm's PHP
 * support ships explicit colors for `VAR`, `PARAMETER`, `INSTANCE_FIELD`,
 * the method/function-call family, etc. in every bundled scheme -- whereas
 * the generic `DefaultLanguageHighlighterColors.LOCAL_VARIABLE` / `PARAMETER`
 * are colorless (default foreground).  That difference is exactly why xphp
 * variables rendered black before.
 *
 * The server's legend (from our `initialize` response), for reference:
 *   types: namespace type class interface enum typeParameter parameter
 *          variable property function method keyword modifier comment
 *          string number operator
 *   modifiers: declaration definition readonly static deprecated abstract
 *
 * Two modifiers refine the mapping where PHP splits a category by them:
 *   - `static`      -> static vs instance field / method-call color
 *   - `declaration` -> a function NAME (`FUNCTION`) vs a call (`FUNCTION_CALL`)
 *
 * NOTE: this only recolors tokens the server actually EMITS.  Class/method
 * *reference* sites the server doesn't tokenize stay default-colored.
 */
class XphpSemanticTokensSupport : LspSemanticTokensSupport() {

    // VERIFY-AGAINST-API: the override below must match
    // `LspSemanticTokensSupport` in the targeted platform (PhpStorm 2026.1 /
    // build 261): `getTextAttributesKey(tokenType: String, modifiers:
    // List<String>): TextAttributesKey?`, returning null to fall back to the
    // platform default.  The compiler flags any mismatch.
    override fun getTextAttributesKey(
        tokenType: String,
        modifiers: List<String>,
    ): TextAttributesKey? {
        val isStatic = "static" in modifiers
        val isDeclaration = "declaration" in modifiers || "definition" in modifiers
        return when (tokenType) {
            // Types: PHP has no generics, so `typeParameter` (and the
            // namespace segment) borrow the class-reference color -- the
            // closest PHP analogue and consistent with how PHP paints types.
            "namespace"             -> Php.CLASS
            "class", "enum", "type" -> Php.CLASS
            "interface"             -> Php.INTERFACE
            "typeParameter"         -> Php.CLASS

            "parameter"             -> Php.PARAMETER
            "variable"              -> Php.VAR
            "property"              -> if (isStatic) Php.STATIC_FIELD else Php.INSTANCE_FIELD
            "function"              -> if (isDeclaration) Php.FUNCTION else Php.FUNCTION_CALL
            "method"                -> if (isStatic) Php.STATIC_METHOD_CALL else Php.INSTANCE_METHOD_CALL

            "keyword", "modifier"   -> Php.KEYWORD
            "comment"               -> Php.COMMENT
            "string"                -> Php.STRING
            "number"                -> Php.NUMBER
            "operator"              -> Php.OPERATION_SIGN

            // Anything outside the legend: defer to the platform default.
            else                    -> super.getTextAttributesKey(tokenType, modifiers)
        }
    }
}
