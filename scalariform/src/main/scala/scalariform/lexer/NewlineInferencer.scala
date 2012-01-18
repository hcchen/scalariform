package scalariform.lexer

import scalariform.lexer.Tokens._

/**
 * Logic for promoting intertoken whitespace/comments to a NEWLINE or NEWLINES token as required.
 */
class NewlineInferencer(private val delegate: WhitespaceAndCommentsGrouper) extends Iterator[Token] {

  import NewlineInferencer._

  //  private var currentToken: Token = _

  private var nextToken: Token = if (delegate.hasNext) delegate.next() else null

  private var previousToken: Token = _

  private var tokenToEmitNextTime: Token = _

  /**
   *  Keeps track of the nested regions which allow multiple statements or not. An item in the stack indicates the
   *  expected closing token type for that region.
   */
  private var regionMarkerStack: List[TokenType] = Nil

  def hasNext = nextToken != null || delegate.hasNext || tokenToEmitNextTime != null

  def next(): Token = {
    val token =
      if (tokenToEmitNextTime == null)
        fetchNextToken()
      else {
        val result = tokenToEmitNextTime
        tokenToEmitNextTime = null
        result
      }
    updateRegionStack(token.tokenType, nextToken)
    previousToken = token
    token
  }

  /**
   * Multiple statements are allowed immediately inside "{..}" regions, but disallowed immediately inside "[..]", "(..)"
   * and "case ... =>" regions.
   */
  private def updateRegionStack(tokenType: TokenType, nextToken: Token) =
    tokenType match {
      case LBRACE ⇒
        regionMarkerStack ::= RBRACE
      case LPAREN ⇒
        regionMarkerStack ::= RPAREN
      case LBRACKET ⇒
        regionMarkerStack ::= RBRACKET
      case CASE if nextToken != null ⇒
        val followingTokenType = nextToken.tokenType
        // "case class" and "case object" are excluded from the usual "case .. =>" region.
        if (followingTokenType != CLASS && followingTokenType != OBJECT)
          regionMarkerStack ::= ARROW
      case tokenType if regionMarkerStack.headOption == Some(tokenType) ⇒
        regionMarkerStack = regionMarkerStack.tail
      case _ ⇒
    }

  private def fetchNextToken(): Token = {
    val token = nextToken
    if (delegate.hasNext)
      nextToken = delegate.next()
    else
      nextToken = null
    if (shouldTranslateToNewline(previousToken, token, nextToken))
      translateToNewline(token)
    else
      token
  }

  private def translateToNewline(currentToken: Token): Token = {
    val currentHiddenTokens = currentToken.associatedWhitespaceAndComments
    val rawText = delegate.text.substring(currentHiddenTokens.offset, currentHiddenTokens.lastCharacterOffset + 1)
    val text = if (currentHiddenTokens.containsUnicodeEscape) currentHiddenTokens.text else rawText
    val tokenType = if (containsBlankLine(text)) NEWLINES else NEWLINE
    val newlineToken = Token(tokenType, text, currentHiddenTokens.offset, rawText)
    currentToken.associatedWhitespaceAndComments_ = NoHiddenTokens
    newlineToken.associatedWhitespaceAndComments_ = currentHiddenTokens
    tokenToEmitNextTime = currentToken
    newlineToken
  }

  private def containsNewline(hiddenTokens: HiddenTokens) =
    hiddenTokens.exists(_.text contains '\n')

  private def shouldTranslateToNewline(previousToken: Token, currentToken: Token, nextToken: Token): Boolean = {
    val hiddenTokens = currentToken.associatedWhitespaceAndComments
    val currentTokenType = currentToken.tokenType
    if (!containsNewline(hiddenTokens))
      return false
    if (TOKENS_WHICH_CANNOT_BEGIN_A_STATEMENT contains currentTokenType)
      return false
    if (currentTokenType == CASE && !followingTokenIsClassOrObject(nextToken))
      return false
    if (regionMarkerStack.nonEmpty && regionMarkerStack.head != RBRACE)
      return false
    else
      return previousToken != null && (TOKENS_WHICH_CAN_END_A_STATEMENT contains previousToken.tokenType)
  }

  private def followingTokenIsClassOrObject(nextToken: Token): Boolean =
    nextToken != null && (nextToken.tokenType == CLASS || nextToken.tokenType == OBJECT)

  private def containsBlankLine(s: String): Boolean = {
    var i = 0
    var inBlankLine = false
    while (i < s.length) {
      val c = s(i)
      if (inBlankLine) {
        if (c == '\n' || c == '\r')
          return true
        else if (c > ' ')
          inBlankLine = false
      } else if (c == '\n' || c == '\r')
        inBlankLine = true
      i += 1
    }
    false
  }

}

object NewlineInferencer {

  private val TOKENS_WHICH_CAN_END_A_STATEMENT: Set[TokenType] = Set(
    INTEGER_LITERAL, FLOATING_POINT_LITERAL, CHARACTER_LITERAL, STRING_LITERAL, SYMBOL_LITERAL, VARID, OTHERID, PLUS,
    MINUS, STAR, PIPE, TILDE, EXCLAMATION, THIS, NULL, TRUE, FALSE, RETURN, TYPE, XML_EMPTY_CLOSE, XML_TAG_CLOSE,
    XML_COMMENT, XML_CDATA, XML_UNPARSED, XML_PROCESSING_INSTRUCTION, USCORE, RPAREN, RBRACKET, RBRACE)

  private val TOKENS_WHICH_CANNOT_BEGIN_A_STATEMENT: Set[TokenType] = Set(
    CATCH, ELSE, EXTENDS, FINALLY, FORSOME, MATCH, REQUIRES, WITH, YIELD, COMMA, DOT, SEMI, COLON, /* USCORE, */ EQUALS,
    ARROW, LARROW, SUBTYPE, VIEWBOUND, SUPERTYPE, HASH, LBRACKET, RPAREN, RBRACKET, RBRACE)

  private val BLANK_LINE_PATTERN = """(?s).*\n\s*\n.*"""

}
