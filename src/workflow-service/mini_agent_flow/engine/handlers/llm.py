from mini_agent_flow.engine.executor import WorkflowExecutionError
from mini_agent_flow.engine.graph_models import GraphLLMNode, GraphNode
from mini_agent_flow.engine.handlers.base import NodeExecutionResult, NodeRuntimeContext


class LLMNodeHandler:
    def execute(self, node: GraphNode, runtime: NodeRuntimeContext) -> NodeExecutionResult:
        if not isinstance(node, GraphLLMNode):
            raise WorkflowExecutionError(f"llm handler cannot execute node: {node.id}")
        prompt = runtime.resolver.resolve_template(node.prompt, runtime.context)
        value = runtime.llm.generate(prompt)
        return NodeExecutionResult(
            input_data={"prompt": prompt},
            outputs={node.output: value},
            publish_patch={node.output: value} if node.publish else {},
        )
