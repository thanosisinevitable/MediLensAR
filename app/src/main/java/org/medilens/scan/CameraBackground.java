package org.medilens.scan;
import android.opengl.GLES20;
import android.opengl.GLES11Ext;
import com.google.ar.core.*;
import java.nio.*;
/** Camera texture background; never replaces the camera with a sample image. */
public final class CameraBackground {
    private int texture,program,position,uv,sampler;
    private final FloatBuffer vertices=buffer(new float[]{-1,-1,1,-1,-1,1,1,1});
    private final FloatBuffer transformed=buffer(new float[8]);
    private static FloatBuffer buffer(float[] data){FloatBuffer b=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(data).position(0);return b;}
    public int texture(){return texture;}
    public void create(){
        int[] t=new int[1];GLES20.glGenTextures(1,t,0);texture=t[0];GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        String vs="attribute vec2 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main(){gl_Position=vec4(a_Position,0.0,1.0);v_TexCoord=a_TexCoord;}";
        String fs="#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES u_Texture;varying vec2 v_TexCoord;void main(){gl_FragColor=texture2D(u_Texture,v_TexCoord);}";
        int vertex=shader(GLES20.GL_VERTEX_SHADER,vs),fragment=shader(GLES20.GL_FRAGMENT_SHADER,fs);program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,fragment);GLES20.glLinkProgram(program);
        int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException("Camera shader link failed");GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(fragment);
        position=GLES20.glGetAttribLocation(program,"a_Position");uv=GLES20.glGetAttribLocation(program,"a_TexCoord");sampler=GLES20.glGetUniformLocation(program,"u_Texture");
    }
    private static int shader(int type,String code){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,code);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException("Camera shader compile failed");return s;}
    public void draw(Frame frame){
        if(frame.getTimestamp()==0)return;
        vertices.position(0);transformed.position(0);frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,vertices,Coordinates2d.TEXTURE_NORMALIZED,transformed);transformed.position(0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);GLES20.glDepthMask(false);GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture);GLES20.glUniform1i(sampler,0);
        GLES20.glEnableVertexAttribArray(position);GLES20.glEnableVertexAttribArray(uv);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,transformed);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glDisableVertexAttribArray(position);GLES20.glDisableVertexAttribArray(uv);GLES20.glDepthMask(true);
    }
}
