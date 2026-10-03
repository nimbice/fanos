package io.github.nimbice.fanos.core.content

import org.junit.Test
import kotlin.test.assertEquals

/** Extraction without a content element, which falls back to finding the chapter on the page. */
class ContentLocatorTest {

    private val comment = "I have been waiting all week for this one and it did not disappoint me in the slightest. ".repeat(6)

    @Test
    fun `the chapter is found, not navigation, comments or sidebars`() {
        // A WordPress translator's blog, with a sidebar and more comment text than chapter text.
        val html = """
            <html><body class="post-template">
            <header id="masthead"><nav><a href="/">Home</a> <a href="/novels">Novels</a></nav>
              <p class="site-description">Translations of long novels, updated weekly by a small team of volunteers.</p></header>
            <div id="page" class="site has-sidebar">
              <main id="main">
                <article class="post">
                  <header class="entry-header"><h1 class="entry-title">Chapter 7 – The Ledger</h1><div class="entry-meta">Posted on May 3 by admin</div></header>
                  <div class="entry-content">
                    <p><a href="/c6">Previous Chapter</a> | <a href="/toc">Index</a> | <a href="/c8">Next Chapter</a></p>
                    <p>$PARAGRAPH</p>
                    <p>Short line.</p>
                    <p>$PARAGRAPH</p>
                    <div class="sharedaddy"><h3>Share this:</h3><ul><li><a href="#">Share on a site</a></li></ul><p>Like this: loading the likes of everyone who liked this post.</p></div>
                  </div>
                </article>
                <div id="comments"><ol class="comment-list">
                  <li><div class="comment-body"><p>$comment</p></div></li><li><div class="comment-body"><p>$comment</p></div></li>
                  <li><div class="comment-body"><p>$comment</p></div></li><li><div class="comment-body"><p>$comment</p></div></li>
                </ol></div>
              </main>
              <div id="secondary" class="widget-area"><section class="widget"><h2>About us</h2><p>$comment</p></section></div>
            </div>
            <footer><p>Everything on this site is shared for fans, by fans, with love and too much coffee.</p></footer>
            </body></html>
        """

        val chapter = extract(html)

        assertEquals(listOf(PARAGRAPH, "Short line.", PARAGRAPH), chapter.texts())
    }

    @Test
    fun `text between line breaks counts, and the page's navigation does not`() {
        // NovelFull's layout: the chapter is bare text in one element, among links and buttons.
        val html = """
            <html><body>
            <div class="navbar"><a href="/">Home</a> <a href="/genres">Genres</a> <a href="/latest">Latest Release</a></div>
            <div id="chapter" class="chapter container"><div class="row"><div class="col-xs-12">
              <a class="truyen-title" href="/glass-orchard.html">The Glass Orchard</a>
              <h2><a class="chapter-title" href="/glass-orchard/chapter-3.html"><span class="chapter-text">Chapter 3</span></a></h2>
              <div class="chapter-nav"><a id="prev_chap" href="/c2"><span>Prev Chapter</span></a><button type="button">Jump</button><a id="next_chap" href="/c4"><span>Next Chapter</span></a></div>
              <div id="chapter-content">$PARAGRAPH<br><br>A second paragraph, shorter.<br><br>$PARAGRAPH</div>
              <div class="text-center"><button type="button" id="chapter_error">Report chapter</button></div>
            </div></div></div>
            <div id="fb-comments"><div class="comment">$comment</div></div>
            </body></html>
        """

        assertEquals(listOf(PARAGRAPH, "A second paragraph, shorter.", PARAGRAPH), extract(html).texts())
    }

    @Test
    fun `a wrapper named like a sidebar does not hide the chapter`() {
        val html = """
            <html><body><div class="wrapper sidebar-right">
              <div class="post"><p>$PARAGRAPH</p><p>$PARAGRAPH</p></div>
              <div class="links"><a href="/a">A link</a> <a href="/b">Another link</a></div>
            </div></body></html>
        """

        assertEquals(listOf(PARAGRAPH, PARAGRAPH), extract(html).texts())
    }

    @Test
    fun `a page rendered by script yields nothing rather than failing`() {
        // PurrFiction: an Angular app; the chapter arrives later, from a script.
        val html = """
            <html><body class="app-initializing">
              <noscript><iframe src="https://www.googletagmanager.com/ns.html?id=GTM-1" height="0" width="0" style="display:none"></iframe></noscript>
              <div id="app-preloader" aria-hidden="true"><div class="logo" title="A site"></div></div><app-root></app-root>
              <noscript>Please enable JavaScript to continue using this application.</noscript>
              <script>window.chapter = { text: "Only here." }</script>
            </body></html>
        """

        assertEquals(emptyList(), extract(html).blocks)
    }
}
