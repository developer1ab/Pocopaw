package com.atombits.pocopaw

import android.widget.FrameLayout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.atombits.pocopaw.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CHAT_BUBBLE_WIDTH_RATIO = 0.7f

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.MessageViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun bind(message: ChatMessage) {
            val context = binding.root.context
            val hasAnswer = message.content.isNotBlank()
            val goalAndPlanContent = message.goalAndPlanContent?.trim().orEmpty()
            val showGoalAndPlan = goalAndPlanContent.isNotBlank()
            val showSearchDetail = false
            val searchSummaryContent = message.searchSummaryContent?.trim().orEmpty()
            val showSearchSummary = searchSummaryContent.isNotBlank()
            val reasoningContent = message.reasoningContent?.trim().orEmpty()
            val showReasoning = reasoningContent.isNotBlank()
            val sourceFooter = formatSourceFooter(message.searchAttribution)
            val showSources = sourceFooter.isNotBlank()
            val turnCapabilitySummary = ChatTurnFormatter.formatCapabilitySummary(
                turnOptions = message.turnOptions,
                thinkingLabel = context.getString(R.string.message_capability_thinking),
                searchLabel = context.getString(R.string.message_capability_search)
            )
            val hasAnswerLabel = message.role == MessageRole.ASSISTANT && hasAnswer
            val showAssistantAvatar = message.role == MessageRole.ASSISTANT
            binding.avatarImage.isVisible = showAssistantAvatar
            binding.roleText.text = message.role.label(context)
            binding.capabilityText.isVisible = turnCapabilitySummary.isNotBlank()
            binding.capabilityText.text = turnCapabilitySummary
            binding.goalPlanSectionContainer.isVisible = showGoalAndPlan
            binding.goalPlanLabelText.isVisible = showGoalAndPlan
            binding.goalPlanText.isVisible = showGoalAndPlan
            binding.goalPlanText.text = if (showGoalAndPlan) goalAndPlanContent else ""
            binding.searchDetailSectionContainer.isVisible = showSearchDetail
            binding.searchDetailLabelText.isVisible = showSearchDetail
            binding.searchDetailText.isVisible = showSearchDetail
            binding.searchDetailText.text = ""
            binding.searchSectionContainer.isVisible = showSearchSummary
            binding.searchLabelText.isVisible = showSearchSummary
            binding.searchText.isVisible = showSearchSummary
            binding.searchText.text = if (showSearchSummary) searchSummaryContent else ""
            binding.reasoningSectionContainer.isVisible = showReasoning
            binding.reasoningLabelText.isVisible = showReasoning
            binding.reasoningText.isVisible = showReasoning
            binding.reasoningText.text = if (showReasoning) reasoningContent else ""
            binding.answerSectionContainer.isVisible = hasAnswer
            binding.contentText.text = message.content
            binding.answerLabelText.isVisible = hasAnswerLabel
            binding.contentText.isVisible = hasAnswer
            binding.sourceSectionContainer.isVisible = showSources
            binding.sourceLabelText.isVisible = showSources
            binding.sourceText.isVisible = showSources
            binding.sourceText.text = sourceFooter
            binding.sourceText.movementMethod = if (showSources) LinkMovementMethod.getInstance() else null
            val tokenText = if (message.role == MessageRole.ASSISTANT) {
                message.tokenUsage?.totalTokens?.let { totalTokens ->
                    context.getString(R.string.message_token_usage, totalTokens)
                }
            } else {
                null
            }
            binding.metaText.text = MessageMetaFormatter.format(
                timestampText = formatter.format(Date(message.timestamp)),
                stageLabel = message.stage?.label(context),
                tokenText = tokenText
            )
            val params = binding.messageCard.layoutParams as FrameLayout.LayoutParams
            when (message.role) {
                MessageRole.USER -> {
                    params.gravity = Gravity.END
                    binding.messageCard.setCardBackgroundColor(context.getColor(R.color.bubble_user))
                    applyBubbleContentAlignment(Gravity.END)
                }
                MessageRole.ASSISTANT -> {
                    params.gravity = Gravity.START
                    binding.messageCard.setCardBackgroundColor(context.getColor(R.color.bubble_assistant))
                    applyBubbleContentAlignment(Gravity.START)
                }
                MessageRole.SYSTEM -> {
                    params.gravity = Gravity.START
                    binding.messageCard.setCardBackgroundColor(context.getColor(R.color.bubble_system))
                    applyBubbleContentAlignment(Gravity.START)
                }
            }
            applyBubbleWidthPolicy(context, message.role, params)
            binding.messageCard.layoutParams = params
        }

        private fun applyBubbleContentAlignment(gravity: Int) {
            binding.goalPlanSectionContainer.gravity = gravity
            binding.searchDetailSectionContainer.gravity = gravity
            binding.searchSectionContainer.gravity = gravity
            binding.reasoningSectionContainer.gravity = gravity
            binding.answerSectionContainer.gravity = gravity
            binding.sourceSectionContainer.gravity = gravity
            binding.roleText.gravity = gravity
            binding.capabilityText.gravity = gravity
            binding.goalPlanLabelText.gravity = gravity
            binding.goalPlanText.gravity = gravity
            binding.searchDetailLabelText.gravity = gravity
            binding.searchDetailText.gravity = gravity
            binding.searchLabelText.gravity = gravity
            binding.searchText.gravity = gravity
            binding.reasoningLabelText.gravity = gravity
            binding.reasoningText.gravity = gravity
            binding.answerLabelText.gravity = gravity
            binding.contentText.gravity = gravity
            binding.sourceLabelText.gravity = gravity
            binding.sourceText.gravity = gravity
            binding.metaText.gravity = gravity
        }

        private fun applyBubbleWidthPolicy(
            context: android.content.Context,
            role: MessageRole,
            params: FrameLayout.LayoutParams
        ) {
            val screenWidth = context.resources.displayMetrics.widthPixels
            when (role) {
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.SYSTEM -> {
                    params.width = (screenWidth * CHAT_BUBBLE_WIDTH_RATIO).toInt()
                }
            }
        }

        private fun formatSourceFooter(attribution: SearchAttribution?): CharSequence {
            val searchAttribution = attribution ?: return ""
            val sources = searchAttribution.sources
            if (sources.isEmpty() && searchAttribution.query.isBlank()) {
                return ""
            }
            val header = buildList {
                searchAttribution.provider.trim().takeIf { it.isNotBlank() }?.let { add(it) }
                searchAttribution.query.trim().takeIf { it.isNotBlank() }?.let { add("query: $it") }
                if (sources.isNotEmpty()) {
                    add("${sources.size} source(s)")
                }
            }.joinToString(" · ")
            return SpannableStringBuilder().apply {
                header.takeIf { it.isNotBlank() }?.let { append(it) }
                sources.take(5).forEachIndexed { index, source ->
                    if (isNotEmpty()) {
                        append('\n')
                    }
                    append("${index + 1}. ")
                    appendClickableSourceTitle(source)
                }
            }
        }

        private fun SpannableStringBuilder.appendClickableSourceTitle(source: SearchAttributionSource) {
            val url = source.url.trim()
            val title = source.title.trim().ifBlank { url }
            if (title.isBlank()) {
                append("-")
                return
            }
            val start = length
            append(title)
            if (url.isNotBlank()) {
                setSpan(URLSpan(url), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean = oldItem == newItem
    }
}