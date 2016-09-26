/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.intentions.loopToCallChain

import com.intellij.openapi.util.Key
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.cfg.pseudocode.Pseudocode
import org.jetbrains.kotlin.cfg.pseudocode.PseudocodeUtil
import org.jetbrains.kotlin.cfg.pseudocode.containingDeclarationForPseudocode
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.idea.analysis.analyzeInContext
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.result.AddToCollectionTransformation
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.result.CountTransformation
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.result.FindTransformationMatcher
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.result.ForEachTransformation
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.sequence.FilterTransformation
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.sequence.FlatMapTransformation
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.sequence.IntroduceIndexMatcher
import org.jetbrains.kotlin.idea.intentions.loopToCallChain.sequence.MapTransformation
import org.jetbrains.kotlin.idea.project.builtIns
import org.jetbrains.kotlin.idea.util.CommentSaver
import org.jetbrains.kotlin.idea.util.FuzzyType
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.getDataFlowInfo
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addToStdlib.check

object MatcherRegistrar {
    val matchers: Collection<TransformationMatcher> = listOf(
            FindTransformationMatcher,
            AddToCollectionTransformation.Matcher,
            CountTransformation.Matcher,
            IntroduceIndexMatcher,
            FilterTransformation.Matcher,
            MapTransformation.Matcher,
            FlatMapTransformation.Matcher,
            ForEachTransformation.Matcher
    )
}

data class MatchResult(
        val sequenceExpression: KtExpression,
        val transformationMatch: TransformationMatch.Result,
        val initializationStatementsToDelete: Collection<KtExpression>
)

fun match(loop: KtForExpression): MatchResult? {
    val (inputVariable, indexVariable, sequenceExpression) = extractLoopData(loop) ?: return null

    var state = createInitialMatchingState(loop, inputVariable, indexVariable) ?: return null

    // used just as optimization to avoid unnecessary checks
    val loopContainsEmbeddedBreakOrContinue = loop.containsEmbeddedBreakOrContinue()

    MatchLoop@
    while (true) {
        state = state.unwrapBlock()

        val inputVariableUsed = state.inputVariable.hasUsages(state.statements)

        // drop index variable if it's not used anymore
        if (state.indexVariable != null && !state.indexVariable!!.hasUsages(state.statements)) {
            state = state.copy(indexVariable = null)
        }

        val restContainsEmbeddedBreakOrContinue = loopContainsEmbeddedBreakOrContinue && state.statements.any { it.containsEmbeddedBreakOrContinue() }

        MatchersLoop@
        for (matcher in MatcherRegistrar.matchers) {
            if (state.indexVariable != null && !matcher.indexVariableAllowed) continue

            val match = matcher.match(state)
            if (match != null) {
                if (!inputVariableUsed && match.allTransformations.any { it.shouldUseInputVariable }) return null

                state.previousTransformations += match.sequenceTransformations

                when (match) {
                    is TransformationMatch.Sequence -> {
                        // check that old input variable is not needed anymore
                        var newState = match.newState
                        if (state.inputVariable != newState.inputVariable && state.inputVariable.hasUsages(newState.statements)) return null

                        if (state.indexVariable != null && match.sequenceTransformations.any { it.affectsIndex }) {
                            // index variable is still needed but index in the new sequence is different
                            if (state.indexVariable!!.hasUsages(newState.statements)) return null
                            newState = newState.copy(indexVariable = null)
                        }

                        if (restContainsEmbeddedBreakOrContinue && !matcher.embeddedBreakOrContinuePossible) {
                            val countBefore = state.statements.sumBy { it.countEmbeddedBreaksAndContinues() }
                            val countAfter = newState.statements.sumBy { it.countEmbeddedBreaksAndContinues() }
                            if (countAfter != countBefore) continue@MatchersLoop // some embedded break or continue in the matched part
                        }

                        state = newState
                        continue@MatchLoop
                    }

                    is TransformationMatch.Result -> {
                        if (restContainsEmbeddedBreakOrContinue && !matcher.embeddedBreakOrContinuePossible) continue@MatchersLoop

                        return TransformationMatch.Result(match.resultTransformation, state.previousTransformations)
                                .let { mergeTransformations(it) }
                                .let { MatchResult(sequenceExpression, it, state.initializationStatementsToDelete) }
                                .check { checkSmartCastsPreserved(loop, it) }
                    }
                }
            }
        }

        return null
    }
}

//TODO: offer to use of .asSequence() as an option
fun convertLoop(loop: KtForExpression, matchResult: MatchResult): KtExpression {
    val resultTransformation = matchResult.transformationMatch.resultTransformation

    val commentSavingRange = resultTransformation.commentSavingRange
    val commentSaver = CommentSaver(commentSavingRange)
    val commentSavingRangeHolder = CommentSavingRangeHolder(commentSavingRange)

    matchResult.initializationStatementsToDelete.forEach { commentSavingRangeHolder.add(it) }

    val callChain = matchResult.generateCallChain(loop)

    commentSavingRangeHolder.remove(loop.unwrapIfLabeled()) // loop will be deleted in all cases
    val result = resultTransformation.convertLoop(callChain, commentSavingRangeHolder)
    commentSavingRangeHolder.add(result)

    for (statement in matchResult.initializationStatementsToDelete) {
        commentSavingRangeHolder.remove(statement)
        statement.delete()
    }

    // we need to adjust indent of the result because in some cases it's made incorrect when moving statement closer to the loop
    commentSaver.restore(commentSavingRangeHolder.range, forceAdjustIndent = true)

    return result
}

data class LoopData(
        val inputVariable: KtCallableDeclaration,
        val indexVariable: KtCallableDeclaration?,
        val sequenceExpression: KtExpression)

private fun extractLoopData(loop: KtForExpression): LoopData? {
    val loopRange = loop.loopRange ?: return null

    val destructuringParameter = loop.destructuringParameter
    if (destructuringParameter != null && destructuringParameter.entries.size == 2) {
        val qualifiedExpression = loopRange as? KtDotQualifiedExpression
        if (qualifiedExpression != null) {
            val call = qualifiedExpression.selectorExpression as? KtCallExpression
            if (call != null && call.calleeExpression.isSimpleName(Name.identifier("withIndex")) && call.valueArguments.isEmpty()) {
                val receiver = qualifiedExpression.receiverExpression
                if (!isExpressionTypeSupported(receiver)) return null
                return LoopData(destructuringParameter.entries[1], destructuringParameter.entries[0], receiver)
            }
        }
    }

    if (!isExpressionTypeSupported(loopRange)) return null

    return LoopData(loop.loopParameter ?: return null, null, loopRange)
}

private fun createInitialMatchingState(
        loop: KtForExpression,
        inputVariable: KtCallableDeclaration,
        indexVariable: KtCallableDeclaration?
): MatchingState? {

    val pseudocodeProvider: () -> Pseudocode = object : () -> Pseudocode {
        val pseudocode: Pseudocode by lazy {
            val declaration = loop.containingDeclarationForPseudocode!!
            val bindingContext = loop.analyze(BodyResolveMode.FULL)
            PseudocodeUtil.generatePseudocode(declaration, bindingContext)
        }

        override fun invoke() = pseudocode
    }

    return MatchingState(
            outerLoop = loop,
            innerLoop = loop,
            statements = listOf(loop.body ?: return null),
            inputVariable = inputVariable,
            indexVariable = indexVariable,
            pseudocodeProvider = pseudocodeProvider
    )
}

private fun isExpressionTypeSupported(expression: KtExpression): Boolean {
    val type = expression.analyze(BodyResolveMode.PARTIAL).getType(expression) ?: return false

    val builtIns = expression.builtIns
    return when {
        type.isSubtypeOf(builtIns.iterable) -> true
        type.isSubtypeOf(builtIns.array) -> true
        KotlinBuiltIns.isPrimitiveArray(type) -> true
        // TODO: support Sequence<T>
        else -> false
    }
}

private fun KotlinType.isSubtypeOf(classDescriptor: ClassDescriptor): Boolean {
    val fuzzyType = FuzzyType(classDescriptor.defaultType, classDescriptor.declaredTypeParameters)
    return fuzzyType.checkIsSuperTypeOf(this) != null
}

private fun checkSmartCastsPreserved(loop: KtForExpression, matchResult: MatchResult): Boolean {
    val bindingContext = loop.analyze(BodyResolveMode.FULL)

    val SMARTCAST_KEY = Key<KotlinType>("SMARTCAST_KEY")
    val IMPLICIT_RECEIVER_SMARTCAST_KEY = Key<KotlinType>("IMPLICIT_RECEIVER_SMARTCAST")

    var smartCastCount = 0
    try {
        loop.forEachDescendantOfType<KtExpression> { expression ->
            bindingContext[BindingContext.SMARTCAST, expression]?.let {
                expression.putCopyableUserData(SMARTCAST_KEY, it)
                smartCastCount++
            }

            bindingContext[BindingContext.IMPLICIT_RECEIVER_SMARTCAST, expression]?.let {
                expression.putCopyableUserData(IMPLICIT_RECEIVER_SMARTCAST_KEY, it)
                smartCastCount++
            }
        }

        if (smartCastCount == 0) return true // optimization

        val callChain = matchResult.generateCallChain(loop)

        val resolutionScope = loop.getResolutionScope(bindingContext, loop.getResolutionFacade())
        val dataFlowInfo = bindingContext.getDataFlowInfo(loop)
        val newBindingContext = callChain.analyzeInContext(resolutionScope, loop, dataFlowInfo = dataFlowInfo)

        val smartCastBroken = callChain.anyDescendantOfType<KtExpression> { expression ->
            val smartCastType = expression.getCopyableUserData(SMARTCAST_KEY)
            if (smartCastType != null) {
                if (newBindingContext[BindingContext.SMARTCAST, expression] != smartCastType && newBindingContext.getType(expression) != smartCastType) {
                    return@anyDescendantOfType true
                }
                smartCastCount--
            }

            val implicitReceiverSmartCastType = expression.getCopyableUserData(IMPLICIT_RECEIVER_SMARTCAST_KEY)
            if (implicitReceiverSmartCastType != null) {
                if (newBindingContext[BindingContext.IMPLICIT_RECEIVER_SMARTCAST, expression] != implicitReceiverSmartCastType) {
                    return@anyDescendantOfType true
                }
                smartCastCount--
            }

            false
        }

        if (smartCastBroken) return false

        assert(smartCastCount >= 0)
        if (smartCastCount > 0) { // not all smart cast expressions has been found in the result, perform more expensive check
            val expressionToBeReplaced = matchResult.transformationMatch.resultTransformation.expressionToBeReplacedByResultCallChain
            if (!tryChangeAndCheckErrors(expressionToBeReplaced, loop) { it.replace(callChain) }) return false
        }

        return true
    }
    finally {
        if (smartCastCount > 0) {
            loop.forEachDescendantOfType<KtExpression> {
                it.putCopyableUserData(SMARTCAST_KEY, null)
                it.putCopyableUserData(IMPLICIT_RECEIVER_SMARTCAST_KEY, null)
            }
        }
    }
}

private fun MatchResult.generateCallChain(loop: KtForExpression): KtExpression {
    var sequenceTransformations = transformationMatch.sequenceTransformations
    var resultTransformation = transformationMatch.resultTransformation
    while(true) {
        val last = sequenceTransformations.lastOrNull() ?: break
        resultTransformation = resultTransformation.mergeWithPrevious(last) ?: break
        sequenceTransformations = sequenceTransformations.dropLast(1)
    }

    val chainCallCount = sequenceTransformations.sumBy { it.chainCallCount } + resultTransformation.chainCallCount
    val lineBreak = if (chainCallCount > 1) "\n" else ""

    var callChain = sequenceExpression

    val psiFactory = KtPsiFactory(loop)
    val chainedCallGenerator = object : ChainedCallGenerator {
        override val receiver: KtExpression
            get() = callChain

        override fun generate(pattern: String, vararg args: Any, receiver: KtExpression, safeCall: Boolean): KtExpression {
            val dot = if (safeCall) "?." else "."
            val newPattern = "$" + args.size + lineBreak + dot + pattern
            return psiFactory.createExpressionByPattern(newPattern, *args, receiver)
        }
    }

    for (transformation in sequenceTransformations) {
        callChain = transformation.generateCode(chainedCallGenerator)
    }

    callChain = resultTransformation.generateCode(chainedCallGenerator)
    return callChain
}

private fun mergeTransformations(match: TransformationMatch.Result): TransformationMatch.Result {
    val transformations = (match.sequenceTransformations + match.resultTransformation).toMutableList()

    var anyChange: Boolean
    do {
        anyChange = false
        for (index in 0..transformations.lastIndex - 1) {
            val transformation = transformations[index] as SequenceTransformation
            val next = transformations[index + 1]
            val merged = next.mergeWithPrevious(transformation) ?: continue
            transformations[index] = merged
            transformations.removeAt(index + 1)
            anyChange = true
            break
        }
    } while(anyChange)

    @Suppress("UNCHECKED_CAST")
    return TransformationMatch.Result(transformations.last() as ResultTransformation, transformations.dropLast(1) as List<SequenceTransformation>)
}

