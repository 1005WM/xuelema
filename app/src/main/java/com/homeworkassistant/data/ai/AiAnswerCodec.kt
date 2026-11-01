package com.homeworkassistant.data.ai

import com.google.gson.*
import com.homeworkassistant.domain.model.*
import java.io.IOException

/** Accept both app and upstream answer schemas, preserving exact option keys and response-unit IDs. */
internal object AiAnswerCodec {
    fun decode(text: String, batch: List<Question>): List<Answer> {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = JsonParser.parseString(clean).asJsonObject
        val array = root.getAsJsonArray("answers") ?: throw IOException("AI 未返回结构化答案")
        val answers = array.map { entry ->
            val json = entry.asJsonObject
            fun string(name: String) = json.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val id = string("questionId") ?: string("id") ?: throw IOException("AI 答案缺少题目标识")
            val question = batch.find { it.id == id } ?: throw IOException("AI 返回未知题目标识")
            val value = json.get("answer") ?: throw IOException("AI 未返回答案")
            val answer = if (value.isJsonArray) {
                val items = value.asJsonArray.map { item ->
                    require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "AI 答案数组格式不完整" }; item.asString
                }
                when (question.type) {
                    QuestionType.MULTIPLE -> items.joinToString(",")
                    QuestionType.MATCHING -> Gson().toJson(items)
                    QuestionType.FILL -> if (items.size == 1) items.single() else Gson().toJson(items)
                    else -> items.singleOrNull() ?: throw IOException("AI 单题答案数量不正确")
                }
            } else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else throw IOException("AI 答案类型无效")
            val rawExplanation = string("explanation")?.takeIf { it.isNotBlank() } ?: string("reason") ?: ""
            require(rawExplanation.length <= 20000) { "AI 答案格式不完整或无法确定答案，请核对原文" }
            val explanation = AiExplanationText.normalize(rawExplanation)
            if (question.type == QuestionType.FILL && explanation.isBlank())
                throw IOException("第 ${question.sourceNumber} 题填空题缺少解题步骤，请点击“补全答案”重试")
            Answer(id, answer, explanation)
        }
        require(answers.map { it.questionId }.toSet() == batch.map { it.id }.toSet() && answers.size == batch.size) { "AI 题号不完整或重复，请重试该批次" }
        require(answers.all { it.answer.isNotBlank() && it.answer.length <= 100000 && it.explanation.length <= 20000 }) { "AI 答案格式不完整或无法确定答案，请核对原文" }
        return answers
    }
}
