#!/bin/bash

# The file containing the sed commands
sed_commands_file="replace.txt"

# The file you want to modify
target_file="$1"

# Read each line from the sed commands file and execute it
while IFS= read -r line
do
    sed -i '' "$line" "$target_file"
done < "$sed_commands_file"
