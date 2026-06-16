package com.waenhancer.ui.components

   import androidx.compose.foundation.BorderStroke
   import androidx.compose.foundation.clickable
   import androidx.compose.foundation.layout.Box
   import androidx.compose.foundation.layout.padding
   import androidx.compose.material3.Surface
   import androidx.compose.runtime.Composable
   import androidx.compose.ui.Modifier
   import androidx.compose.ui.unit.dp
   import com.waenhancer.ui.designsystem.WaexTheme

   @Composable
   fun WaexCard(
       modifier: Modifier = Modifier,
       onClick: (() -> Unit)? = null,
       content: @Composable () -> Unit
   ) {
       val colors = WaexTheme.colors
       val radius = WaexTheme.radius
       val elevation = WaexTheme.elevation

       Surface(
           modifier = modifier,
           shape = radius.cardShape,
           color = colors.surfaceContainerLow,
           border = BorderStroke(1.dp, colors.outlineVariant),
           shadowElevation = elevation.card
       ) {
           val contentModifier = if (onClick != null) {
               Modifier
                   .clickable(onClick = onClick)
                   .padding(20.dp)
           } else {
               Modifier.padding(20.dp)
           }
           Box(modifier = contentModifier) {
               content()
           }
       }
   }
