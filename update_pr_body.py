import json

# Read the PR body
with open('current_pr_body.txt', 'r', encoding='utf-8') as f:
    body = f.read()

# Find and replace the ui-review block's head field
import re
old_head = '"head": "65b3a9c3e425e363a88f3c3582ea5a46cd4392d2"'
new_head = '"head": "60630c4a7c6b3d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0"'

# Use the real new head SHA from git
new_head_sha = '60630c485295ff8afc968d89aff677ad27583407'
new_head = f'"head": "{new_head_sha}"'

body_updated = body.replace(old_head, new_head)

# Write the updated body
with open('updated_pr_body.txt', 'w', encoding='utf-8') as f:
    f.write(body_updated)

print(f"Updated head from 65b3a9c... to {new_head_sha}")
