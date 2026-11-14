# Minimal FlatBuffers reader for .tflite files (no TensorFlow required).
# Usage: powershell -File tools/inspect-tflite.ps1 app/src/main/assets/yamnet.tflite
param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $Path))

function Read-U32([int]$pos) { [BitConverter]::ToUInt32($bytes, $pos) }
function Read-I32([int]$pos) { [BitConverter]::ToInt32($bytes, $pos) }
function Read-U16([int]$pos) { [BitConverter]::ToUInt16($bytes, $pos) }

function Get-Root([int]$pos) { $pos + (Read-U32 $pos) }

function Get-Field([int]$table, [int]$index) {
    $vtable = $table - (Read-I32 $table)
    $vtableSize = Read-U16 $vtable
    $entry = 4 + $index * 2
    if ($entry -ge $vtableSize) { return $null }
    $offset = Read-U16 ($vtable + $entry)
    if ($offset -eq 0) { return $null }
    return $table + $offset
}

function Get-Vector([int]$fieldPos) {
    $vector = $fieldPos + (Read-U32 $fieldPos)
    $length = Read-U32 $vector
    return @{ Start = $vector + 4; Length = $length }
}

function Get-String([int]$fieldPos) {
    $vector = $fieldPos + (Read-U32 $fieldPos)
    $length = Read-U32 $vector
    return [Text.Encoding]::UTF8.GetString($bytes, $vector + 4, $length)
}

$model = Get-Root 0
$descriptionField = Get-Field $model 3
if ($descriptionField) { "description: " + (Get-String $descriptionField) }

$subgraphsField = Get-Field $model 2
$subgraphs = Get-Vector $subgraphsField
"subgraphs: $($subgraphs.Length)"

for ($s = 0; $s -lt $subgraphs.Length; $s++) {
    $subgraph = Get-Root ($subgraphs.Start + $s * 4)
    $tensorsField = Get-Field $subgraph 0
    $tensors = Get-Vector $tensorsField

    "--- subgraph $s : $($tensors.Length) tensors"

    $inputsField = Get-Field $subgraph 1
    $inputs = if ($inputsField) { Get-Vector $inputsField } else { @{ Start = 0; Length = 0 } }
    $inputIds = @()
    for ($i = 0; $i -lt $inputs.Length; $i++) { $inputIds += Read-I32 ($inputs.Start + $i * 4) }

    $outputsField = Get-Field $subgraph 2
    $outputs = if ($outputsField) { Get-Vector $outputsField } else { @{ Start = 0; Length = 0 } }
    $outputIds = @()
    for ($i = 0; $i -lt $outputs.Length; $i++) { $outputIds += Read-I32 ($outputs.Start + $i * 4) }

    for ($t = 0; $t -lt $tensors.Length; $t++) {
        $tensor = Get-Root ($tensors.Start + $t * 4)
        $nameField = Get-Field $tensor 3
        $name = if ($nameField) { Get-String $nameField } else { "" }
        $shapeField = Get-Field $tensor 0
        $shape = if ($shapeField) {
            $v = Get-Vector $shapeField
            @(for ($d = 0; $d -lt $v.Length; $d++) { Read-I32 ($v.Start + $d * 4) })
        } else { @() }
        $typeField = Get-Field $tensor 1
        $type = if ($typeField) { $bytes[$typeField] } else { 0 }

        $role = @()
        if ($inputIds -contains $t) { $role += "INPUT" }
        if ($outputIds -contains $t) { $role += "OUTPUT" }

        $shapeText = ($shape -join "x")
        $typeName = switch ($type) { 0 { "FLOAT32" } 1 { "FLOAT16" } 2 { "INT32" } 3 { "UINT8" } 4 { "INT64" } 9 { "INT8" } default { "type=$type" } }
        "[$t] $typeName [$shapeText] $name $($role -join ',')"
    }
}
