package io.github.nekohasekai.pm.instance

import cn.hutool.core.date.SystemClock
import io.github.nekohasekai.nekolib.core.client.TdException
import io.github.nekohasekai.nekolib.core.client.TdHandler
import io.github.nekohasekai.nekolib.core.raw.*
import io.github.nekohasekai.nekolib.core.utils.*
import io.github.nekohasekai.nekolib.i18n.DELETE
import io.github.nekohasekai.nekolib.i18n.DELETED
import io.github.nekohasekai.nekolib.i18n.failed
import io.github.nekohasekai.pm.*
import io.github.nekohasekai.pm.database.MessageRecords
import io.github.nekohasekai.pm.database.PmInstance
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import td.TdApi

class OutputHandler(pmInstance: PmInstance) : TdHandler(), PmInstance by pmInstance {

    companion object {

        const val dataId = DATA_DELETE_MESSAGE

    }

    override fun onLoad() {

        initData(dataId)

    }

    var currentChat = 0L

    override suspend fun onNewMessage(userId: Int, chatId: Long, message: TdApi.Message) {

        val integration = integration

        val useIntegration = chatId == integration?.integration

        if (chatId != admin && !useIntegration) return

        fun saveSent(targetChat: Long, sentMessageId: Long) {

            database.write {

                MessageRecords.insert {

                    it[messageId] = message.id

                    it[type] = MESSAGE_TYPE_OUTPUT_MESSAGE

                    it[this.chatId] = targetChat

                    it[targetId] = sentMessageId

                    it[createAt] = (SystemClock.now() / 100L).toInt()

                    it[botId] = me.id

                }

                MessageRecords.insert {

                    it[messageId] = sentMessageId

                    it[type] = MESSAGE_TYPE_OUTPUT_FORWARDED

                    it[this.chatId] = targetChat

                    it[targetId] = message.id

                    it[createAt] = (SystemClock.now() / 100L).toInt()

                    it[botId] = me.id

                }

            }

        }

        if (message.replyToMessageId == 0L) {

            if (currentChat == 0L) {

                if (sudo is PmBot && chatId == admin) {

                    sudo make L.PM_HELP syncReplyTo message

                }

                return

            }

            if (useIntegration && integration!!.adminOnly && checkChatAdmin(message)) return

            val sentMessage = try {

                sudo make message.asInputOrForward syncTo currentChat

            } catch (e: TdException) {

                sudo make L.failed { e.message } onSuccess deleteDelay(message) replyTo message

                return

            }

            userCalled(userId, "发出消息: ${sentMessage.text ?: "<${sentMessage.content.javaClass.simpleName.substringAfter("Message")}>"}")

            saveSent(currentChat, sentMessage.id)

            (sudo make L.SENT).apply {

                if (settings?.keepActionMessages == true) {

                    if (settings.ignoreDeleteAction) {

                        withMarkup(inlineButton {

                            dataLine(L.DELETE, dataId, sentMessage.chatId.toByteArray(), sentMessage.id.toByteArray())

                        })

                    }

                } else {

                    onSuccess = deleteDelay()

                }

            } replyTo message

            return

        }

        if (useIntegration && integration!!.adminOnly && checkChatAdmin(message)) return

        val record = database {

            MessageRecords.select { messagesForCurrentBot and (MessageRecords.messageId eq message.replyToMessageId) }.firstOrNull()

        }

        if (record == null) {

            if (useIntegration && getMessageOrNull(chatId, message.replyToMessageId)?.let { getChatMemberOrNull(chatId, it.senderUserId)?.status?.isMember } == true) {

                // 如果回复的用户在群组里即跳过找不到记录提示

                return

            }

            sudo make L.RECORD_NF onSuccess deleteDelayIf(settings?.keepActionMessages != true) replyTo message

            return

        }

        suspend fun getTargetChat() = try {

            getChat(record[MessageRecords.chatId]).id

        } catch (e: TdException) {

            sudo make L.failed { USER_NOT_FOUND } onSuccess deleteDelayIf(settings?.keepActionMessages != true) replyTo message

            null

        }

        when (record[MessageRecords.type]) {

            MessageRecords.MESSAGE_TYPE_INPUT_MESSAGE -> {

                defaultLog.warn("Please delete the data after changing the bot admin.")

            }

            MessageRecords.MESSAGE_TYPE_INPUT_NOTICE -> {

                val targetChat = getTargetChat() ?: return

                val sentMessage = try {

                    sudo make message.content.asInput!! syncTo targetChat

                } catch (e: TdException) {

                    sudo make L.failed { e.message } onSuccess deleteDelayIf(settings?.keepActionMessages != true) replyTo message

                    return

                }

                userCalled(userId, "发出消息: ${sentMessage.text ?: "<${sentMessage.content.javaClass.simpleName.substringAfter("Message")}>"}")

                saveSent(targetChat, sentMessage.id)

                (sudo make L.SENT).apply {

                    if (settings?.keepActionMessages == true) {

                        if (settings.ignoreDeleteAction) {

                            withMarkup(inlineButton {

                                dataLine(L.DELETE, dataId, sentMessage.chatId.toByteArray(), sentMessage.id.toByteArray())

                            })

                        }

                    } else {

                        onSuccess = deleteDelay()

                    }

                } replyTo message

            }

            MessageRecords.MESSAGE_TYPE_INPUT_FORWARDED,
            MessageRecords.MESSAGE_TYPE_OUTPUT_FORWARDED -> {

                val targetChat = getTargetChat() ?: return

                val reply = currentChat == targetChat || settings?.keepReply == true
                var targetMessage = 0L

                if (reply) {

                    targetMessage = try {

                        getMessage(targetChat, record[MessageRecords.targetId]!!).id

                    } catch (e: TdException) {

                        sudo make L.failed { REPLIED_NF } onSuccess deleteDelayIf(settings?.keepActionMessages != true) replyTo message

                        return

                    }

                }

                val sentMessage = try {

                    sudo make message.content.asInput!! replyAt targetMessage syncTo targetChat

                } catch (e: TdException) {

                    sudo make L.failed { e.message } onSuccess deleteDelayIf(settings?.keepActionMessages != true) replyTo message

                    return

                }

                userCalled(userId, "发出消息: ${sentMessage.text ?: "<${sentMessage.content.javaClass.simpleName.substringAfter("Message")}>"}")

                saveSent(targetChat, sentMessage.id)

                (sudo make if (targetMessage != 0L) L.REPLIED else L.SENT).apply {

                    if (settings?.keepActionMessages == true) {

                        if (settings.ignoreDeleteAction) {

                            withMarkup(inlineButton {

                                dataLine(L.DELETE, dataId, sentMessage.chatId.toByteArray(), sentMessage.id.toByteArray())

                            })

                        }

                    } else {

                        onSuccess = deleteDelay()

                    }

                } replyTo message

            }

        }

    }

    override suspend fun onNewCallbackQuery(userId: Int, chatId: Long, messageId: Long, queryId: Long, data: Array<ByteArray>) {

        val useIntegration = chatId == integration?.integration

        if (chatId != admin && !useIntegration) return

        if (useIntegration && integration!!.adminOnly && checkChatAdmin(chatId, userId, queryId)) return

        val targetChat = data[0].toLong()
        val targetMessage = data[1].toLong()

        database.write {

            MessageRecords.deleteWhere { messagesForCurrentBot and (MessageRecords.messageId inList listOf(messageId, targetMessage)) }

        }

        getMessageWith(targetChat, targetMessage) {

            onSuccess {

                sudo makeAnswer L.DELETED answerTo queryId

                if (useIntegration) {

                    sudo makeHtml L.MESSAGE_DELETED_BY.input(getUser(userId).asInlineMention) at messageId editTo chatId

                } else {

                    sudo make L.MESSAGE_DELETED_BY_ME at messageId editTo chatId

                }

                sudo delete it

            }

            onFailure {

                sudo makeAlert L.RECORD_NF answerTo queryId

                editMessageReplyMarkup(chatId, messageId, null)

            }

        }


    }

}
