package to.joeli.jass.client.strategy.training.networks

import org.slf4j.LoggerFactory
import org.tensorflow.SavedModelBundle
import org.tensorflow.ndarray.StdArrays
import org.tensorflow.types.TFloat32
import to.joeli.jass.client.strategy.helpers.ShellScriptRunner
import to.joeli.jass.client.strategy.training.NetworkType
import to.joeli.jass.client.strategy.training.data.DataSet


/**
 * Loads TF2 SavedModel exported by Keras via tf.saved_model.save().
 * Uses the "serving_default" signature for inference.
 */
open class NeuralNetwork(private val networkType: NetworkType, var isTrainable: Boolean) {

    var savedModelBundle: SavedModelBundle? = null


    fun loadModel(episode: Int) {
        val path = "${DataSet.getEpisodePath(episode)}${networkType.path}models/export/"
        savedModelBundle = SavedModelBundle.load(path, "serve")
    }

    @Synchronized
    fun predict(features: Array<FloatArray>): TFloat32 {
        if (savedModelBundle == null)
            throw IllegalStateException("There is no neural network loaded! Cannot make any predictions!")

        // Wrap (H, W) features as (1, H, W) batch.
        val input = Array(1) { features }
        val inputTensor = TFloat32.tensorOf(StdArrays.ndCopyOf(input))

        val result = savedModelBundle!!.session().runner()
                .feed(networkType.inputName, inputTensor)
                .fetch(networkType.outputName)
                .run()[0]

        inputTensor.close()
        return result as TFloat32
    }

    companion object {
        /**
         * Trains the network with a given train mode. The actual training is done in python with keras. This is why we invoke the shell script.
         */
        @JvmStatic
        fun train(episode: Int, networkType: NetworkType): Boolean {
            return ShellScriptRunner.runShellProcess(ShellScriptRunner.pythonDirectory, "python3 train.py ${zeroPadded(episode)} ${networkType.path}")

        }

        private fun zeroPadded(number: Int): String {
            return String.format("%04d", number)
        }

        val logger = LoggerFactory.getLogger(NeuralNetwork::class.java)
    }
}
