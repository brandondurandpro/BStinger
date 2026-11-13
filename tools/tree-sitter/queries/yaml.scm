; Patterns of this repository added after the grammar's own (so they win): the keys of a mapping are colored apart
; from its values (the scalars' patterns come later in Helix's query), as are anchors, aliases and tags.
(block_mapping_pair
  key: (flow_node (plain_scalar (_) @variable.other.member)))
(block_mapping_pair
  key: (flow_node [(double_quote_scalar) (single_quote_scalar)] @variable.other.member))
(flow_mapping
  (_ key: (flow_node (plain_scalar (_) @variable.other.member))))
(flow_mapping
  (_ key: (flow_node [(double_quote_scalar) (single_quote_scalar)] @variable.other.member)))
(anchor_name) @label
(alias_name) @label
(tag) @attribute
