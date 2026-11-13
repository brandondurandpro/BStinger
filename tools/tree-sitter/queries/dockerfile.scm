; Patterns of this repository added after the grammar's own (so they win): what Helix's query leaves uncolored.
(image_spec name: (image_name) @tag)
(image_alias) @label
(path) @string.special.path
(arg_instruction name: (unquoted_string) @variable.other.member)
(env_pair name: (unquoted_string) @variable.other.member)
(label_pair key: (_) @variable.other.member)
(expansion (variable) @constant)
