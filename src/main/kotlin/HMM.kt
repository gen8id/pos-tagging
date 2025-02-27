import org.jetbrains.kotlinx.multik.api.empty
import org.jetbrains.kotlinx.multik.api.mk
import org.jetbrains.kotlinx.multik.ndarray.data.*
import kotlin.math.ln


class HMM(private val trainingCorpus: List<String>, private val vocab: Map<String, Int>) {

    // transitionCounts stores how often a sequence of two tags occurs for each possible sequence of two tags
    private val transitionCounts = mutableMapOf<Pair<String, String>, Int>()
    // emissionCounts stores how often a combinations of a tag and a word occurs for each tag and word combo
    private val emissionCounts = mutableMapOf<Pair<String, String>, Int>()
    // tagCounts stores how often each tag is encountered in the trainingCorpus
    private val tagCounts = mutableMapOf<String, Int>()

    private var NUMBER_OF_TAGS = 0
    private val NUMBER_OF_WORDS = vocab.size

    private lateinit var transitionMatrix: D2Array<Double>
    private lateinit var emissionProbsMatrix: D2Array<Double>

    init {
        calculateCounts()
        NUMBER_OF_TAGS = tagCounts.size
        createTransitionMatrix()
        createEmissionProbsMatrix()
    }

    private fun calculateCounts() {
        val preprocessor = Preprocessor
        var previousTag = "--s--"
        for (line in trainingCorpus) {
            val (word, tag) = preprocessor.getWordAndTagFromLine(line, vocab)
            transitionCounts[Pair(previousTag, tag)] = transitionCounts.getOrDefault(Pair(previousTag, tag), 0) + 1
            emissionCounts[Pair(tag, word)] = emissionCounts.getOrDefault(Pair(tag, word), 0) + 1
            tagCounts[tag] = tagCounts.getOrDefault(tag, 0) + 1
            previousTag = tag
        }
    }

    private fun createTransitionMatrix(
        alpha: Double = 0.001,
    ) {
        val tags = tagCounts.keys.toList().sorted()

        transitionMatrix = mk.empty<Double, D2>(NUMBER_OF_TAGS, NUMBER_OF_TAGS)

        // Go through each row and column of the transition matrix
        for (i in 0 until NUMBER_OF_TAGS) for (j in 0 until NUMBER_OF_TAGS) {

            // Define the Pair (prev POS tag, current POS tag)
            val key = Pair(tags[i], tags[j])

            // If the (prev POS tag, current POS tag) exists in the transition counts dictionary, change the count
            val count = transitionCounts.getOrDefault(key, 0)

            // Get the count of the previous tag (index position i) from tag counts
            val countPrevTag = tagCounts[tags[i]]

            // Apply smoothing to avoid numeric underflow
            transitionMatrix[i, j] = (count + alpha) / (alpha * NUMBER_OF_TAGS + countPrevTag!!)
        }
    }

    private fun createEmissionProbsMatrix(
        alpha: Double = 0.001
    ) {
        val tags = tagCounts.keys.toList().sorted()

        emissionProbsMatrix = mk.empty<Double, D2>(NUMBER_OF_TAGS, NUMBER_OF_WORDS)
        val reversedVocab = vocab.entries.associate { (k, v) -> v to k }

        for (i in 0 until NUMBER_OF_TAGS) for (j in 0 until NUMBER_OF_WORDS) {

            val key = Pair(tags[i], reversedVocab[j])
            val count = emissionCounts.getOrDefault(key, 0)
            val countTag = tagCounts[tags[i]]
            emissionProbsMatrix[i, j] = (count + alpha) / (alpha * NUMBER_OF_WORDS + countTag!!)
        }
    }

    /**
     * returns two matrices: bestProbs = best probabilities (num of states by num of words in sentence) and
     *   bestPaths = best paths (num of states by num of words in sentence)
     */
    private fun initializeViterbiMatrices(
        sentence: List<String>,
    ): Pair<D2Array<Double>, D2Array<Int>> {

        val tags = tagCounts.keys.toList().sorted()
        val bestProbs = mk.empty<Double, D2>(NUMBER_OF_TAGS, sentence.size)
        val bestPaths = mk.empty<Int, D2>(NUMBER_OF_TAGS, sentence.size)

        val startIdx = tags.indexOf("--s--")

        // populating the first column of the bestProbs to initialize it
        for (i in 0 until NUMBER_OF_TAGS) {
            if (transitionMatrix[0, i] == 0.0) {
                bestProbs[i, 0] = Double.NEGATIVE_INFINITY
            } else {
                bestProbs[i, 0] = ln(transitionMatrix[startIdx, i]) + ln(emissionProbsMatrix[i, vocab[sentence[0]]!!])
            }
        }
        return Pair(bestProbs, bestPaths)
    }

    private fun viterbiForward(
        sentence: List<String>,
        bestProbs: D2Array<Double>,
        bestPaths: D2Array<Int>
    ): Pair<D2Array<Double>, D2Array<Int>> {

        val updatedProbs = bestProbs
        val updatedPaths = bestPaths

        for (i in 1 until sentence.size) for (j in 0 until NUMBER_OF_TAGS) {

            var bestProbabilityToGetToWordIFromTagJ = Double.NEGATIVE_INFINITY
            var bestPathToWordI = 0

            for (k in 0 until NUMBER_OF_TAGS) {

                val temp_prob =
                    updatedProbs[k, i - 1] + ln(transitionMatrix[k, j]) + ln(emissionProbsMatrix[j, vocab[sentence[i]]!!])

                if (temp_prob > bestProbabilityToGetToWordIFromTagJ) {
                    bestProbabilityToGetToWordIFromTagJ = temp_prob
                    bestPathToWordI = k
                }
            }
            updatedProbs[j, i] = bestProbabilityToGetToWordIFromTagJ
            updatedPaths[j, i] = bestPathToWordI
        }
        return Pair(updatedProbs, updatedPaths)
    }

    private fun viterbiBackward(
        sentence: List<String>,
        bestProbs: D2Array<Double>,
        bestPaths: D2Array<Int>
    ): List<String> {
        val m = sentence.size
        val z = IntArray(m)
        var bestProbForLastWord = Double.NEGATIVE_INFINITY
        val tags = tagCounts.keys.toList().sorted()

        val posPredictions = mutableListOf<String>()

        for (k in 0 until NUMBER_OF_TAGS) {

            // finding the index of the cell with the highest probability in the last column of the bestProbs
            if (bestProbs[k, m - 1] > bestProbForLastWord) {
                bestProbForLastWord = bestProbs[k, m - 1]
                z[m - 1] = k
            }
        }
        posPredictions.add(tags[z[m - 1]])

        // traversing the bestPaths backwards.
        // each current cell contains the row index of the cell to go to in the next column
        for (i in m - 1 downTo 1) {
            val tagForWordI = bestPaths[z[i], i]
            z[i - 1] = tagForWordI
            posPredictions.add(tags[tagForWordI])
        }
        return posPredictions.toList().reversed()
    }

    fun predictPOSSequence(sentence: List<String>): List<String> {
        val (initialBestProbs, initialBestPaths) = initializeViterbiMatrices(sentence)
        val (updatedBestProbs, updatedBestPaths) = viterbiForward(sentence, initialBestProbs, initialBestPaths)
        return viterbiBackward(sentence, updatedBestProbs, updatedBestPaths)
    }


    fun score(testWords: List<String>, testTags: List<String>): Double {
        require(testWords.size == testTags.size) { "The size of testWords list doesn't match the size of the testTags list" }

        val predictions = predictPOSSequence(testWords)
        val numberOfCorrectPredictions = predictions.zip(testTags).count { it.first == it.second }

        return numberOfCorrectPredictions.toDouble() / predictions.size
    }

}