param(
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\app\build\reports\ai-identification\identificador-sintetico.png')
)

# Deterministic local fixture. It never reads a customer image or contacts a service.
Add-Type -AssemblyName System.Drawing
$fixtureOutput = [System.IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $fixtureOutput) {
    throw "El archivo ya existe. Elige una ruta nueva: $fixtureOutput"
}
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($fixtureOutput)) | Out-Null
$bitmap = New-Object System.Drawing.Bitmap 1200,720
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$heading = New-Object System.Drawing.Font 'Arial',26,([System.Drawing.FontStyle]::Bold)
$identifier = New-Object System.Drawing.Font 'Arial',88,([System.Drawing.FontStyle]::Bold)
$iso = New-Object System.Drawing.Font 'Arial',42,([System.Drawing.FontStyle]::Bold)
$caption = New-Object System.Drawing.Font 'Arial',22
$black = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(25,30,38))
$navy = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(25,51,81))
$white = [System.Drawing.Brushes]::White
try {
    $graphics.Clear([System.Drawing.Color]::FromArgb(232,237,242))
    $graphics.FillRectangle($navy, 42, 42, 1116, 636)
    $graphics.FillRectangle($white, 78, 130, 1044, 432)
    $graphics.DrawString('PRUEBA SINTETICA DE IDENTIFICACION', $heading, $white, 84, 72)
    $graphics.DrawString('TSTU4855933', $identifier, $black, 122, 250)
    $graphics.DrawString('22G1', $iso, $black, 834, 416)
    $graphics.DrawString('Imagen ficticia de prueba - sin datos de clientes', $caption, $white, 87, 592)
    $bitmap.Save($fixtureOutput, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $black.Dispose()
    $navy.Dispose()
    $heading.Dispose()
    $identifier.Dispose()
    $iso.Dispose()
    $caption.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}
$fixtureInfo = Get-Item -LiteralPath $fixtureOutput
[pscustomobject]@{
    Path = $fixtureInfo.FullName
    ByteSize = $fixtureInfo.Length
    Sha256 = (Get-FileHash -LiteralPath $fixtureOutput -Algorithm SHA256).Hash
}
