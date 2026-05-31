package to.joeli.jass.client.strategy.training

/**
 * The network type sets the path in the python environment.
 *
 * inputName / outputName are the tensor names in the serving_default signature.
 * These come from model.export() (Keras 3). To rediscover if the model changes:
 *   from tensorflow.python.saved_model import loader_impl
 *   saved = loader_impl.parse_saved_model(export_path)
 *   sd = saved.meta_graphs[0].signature_def['serving_default']
 *   print({k: v.name for k, v in sd.inputs.items()})
 *   print({k: v.name for k, v in sd.outputs.items()})
 */
enum class NetworkType constructor(
    val path: String,
    val inputName: String,
    val outputName: String
) {
    CARDS("cards/", "serving_default_input:0", "StatefulPartitionedCall_1:0"),
    SCORE("score/", "serving_default_input:0", "StatefulPartitionedCall_1:0"),
    POLICY("policy/", "serving_default_input:0", "StatefulPartitionedCall_1:0"),
}
