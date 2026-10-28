package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.audio.R

/**
 * قارئ المقالات النظيف (ع٥٦-أمر 7).
 * يستخرج كتلة المحتوى الرئيسية ويقرأها متواصلاً بفقرات.
 */
object ArticleReader {

    const val MIN_CONTENT_CHAR_COUNT = 80
    const val MAX_IGNORE_CHAR_COUNT = 40

    private val AD_NAV_KEYWORDS = setOf(
        "إعلان",
        "advertisement",
        "menu",
        "share",
        "مشاركة",
        "قائمة",
        "تابعنا",
        "اشترك",
        "subscribe"
    )

    data class Paragraph(
        val text: String,
        val bounds: Rect
    )

    private var activeParagraphs = listOf<Paragraph>()
    private var currentIndex = 0
    private var isReading = false
    private var isPaused = false

    /**
     * استخراج فقرات المقال من شجرة العقد.
     */
    fun extractArticle(
        root: AccessibilityNodeInfo?,
        isRtl: Boolean = true
    ): List<Paragraph> {
        if (root == null) return emptyList()

        val rawList = mutableListOf<Paragraph>()
        collectCandidateNodes(root, rawList)

        if (rawList.isEmpty()) return emptyList()

        // ترتيب من الأعلى إلى الأسفل وبحسب RTL
        val sorted = rawList.sortedWith { a, b ->
            val yDiff = a.bounds.top.compareTo(b.bounds.top)
            if (yDiff != 0) {
                yDiff
            } else {
                if (isRtl) {
                    b.bounds.right.compareTo(a.bounds.right)
                } else {
                    a.bounds.left.compareTo(b.bounds.left)
                }
            }
        }
        return sorted
    }

    private fun collectCandidateNodes(
        node: AccessibilityNodeInfo?,
        outList: MutableList<Paragraph>
    ) {
        if (node == null) return
        if (node.isPassword || !node.isVisibleToUser) return

        val text = extractText(node)
        if (text.length >= MIN_CONTENT_CHAR_COUNT && !isAdOrNav(text)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            outList.add(Paragraph(text, rect))
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            collectCandidateNodes(child, outList)
        }
    }

    fun isAdOrNav(text: String): Boolean {
        val lower = text.lowercase()
        return AD_NAV_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * بدء قراءة المقال في الشاشة الحالية.
     */
    fun startReading(
        context: Context,
        root: AccessibilityNodeInfo?,
        announceParagraphNum: Boolean = false,
        isRtl: Boolean = true
    ): Boolean {
        val paragraphs = extractArticle(root, isRtl)
        if (paragraphs.isEmpty()) {
            val msg = context.getString(R.string.article_reader_no_content)
            A11ySpeechCoordinator.speakAnnouncement(context, msg)
            return false
        }

        activeParagraphs = paragraphs
        currentIndex = 0
        isReading = true
        isPaused = false

        readCurrentParagraph(context, announceParagraphNum)
        return true
    }

    private var currentCompletionListener: (() -> Unit)? = null

    private fun readCurrentParagraph(
        context: Context,
        announceParagraphNum: Boolean
    ) {
        if (currentIndex !in activeParagraphs.indices) {
            stop(context)
            return
        }
        val paragraph = activeParagraphs[currentIndex]
        val textToSpeak = if (announceParagraphNum) {
            context.getString(
                R.string.article_reader_paragraph,
                currentIndex + 1,
                paragraph.text
            )
        } else {
            paragraph.text
        }
        val speaker = AnnouncementSpeaker.getInstance(context)
        currentCompletionListener?.let { speaker.removeCompletionListener(it) }
        val listener = object : () -> Unit {
            override fun invoke() {
                speaker.removeCompletionListener(this)
                if (isReading && !isPaused) {
                    currentIndex++
                    readCurrentParagraph(context, announceParagraphNum)
                }
            }
        }
        currentCompletionListener = listener
        speaker.addCompletionListener(listener)
        A11ySpeechCoordinator.speakAnnouncement(context, textToSpeak)
    }

    fun togglePause(context: Context) {
        if (!isReading) return
        isPaused = !isPaused
        if (isPaused) {
            currentCompletionListener?.let {
                AnnouncementSpeaker.getInstance(context)
                    .removeCompletionListener(it)
            }
            AnnouncementSpeaker.getInstance(context).stop()
        } else {
            readCurrentParagraph(context, announceParagraphNum = false)
        }
    }

    fun nextParagraph(context: Context) {
        if (!isReading || activeParagraphs.isEmpty()) return
        if (currentIndex < activeParagraphs.size - 1) {
            currentIndex++
            readCurrentParagraph(context, announceParagraphNum = false)
        }
    }

    fun previousParagraph(context: Context) {
        if (!isReading || activeParagraphs.isEmpty()) return
        if (currentIndex > 0) {
            currentIndex--
            readCurrentParagraph(context, announceParagraphNum = false)
        }
    }

    fun stop(context: Context? = null) {
        isReading = false
        isPaused = false
        currentIndex = 0
        activeParagraphs = emptyList()
        context?.let { ctx ->
            currentCompletionListener?.let {
                AnnouncementSpeaker.getInstance(ctx)
                    .removeCompletionListener(it)
            }
        }
        currentCompletionListener = null
    }

    private fun extractText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        val cd = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            t.isNotBlank() && cd.isNotBlank() && t != cd -> "$cd $t"
            t.isNotBlank() -> t
            cd.isNotBlank() -> cd
            else -> ""
        }
    }

    @androidx.annotation.VisibleForTesting
    fun isReadingActive(): Boolean = isReading
}
