package com.atombits.pocopaw.shizuku;

import android.os.Bundle;

interface IShizukuCommandService {
    void destroy() = 16777114;
    Bundle runCommand(in String[] command) = 1;
}