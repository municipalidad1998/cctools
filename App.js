import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, FlatList, Image, TextInput, TouchableOpacity, Alert, ScrollView } from 'react-native';
import * as MediaLibrary from 'expo-media-library';
import * as FileSystem from 'expo-file-system';
import axios from 'axios';

export default function App() {
  const [hasPermission, setHasPermission] = useState(null);
  const [assets, setAssets] = useState([]);
  const [token, setToken] = useState('');
  const [chatId, setChatId] = useState('');
  const [isBackingUp, setIsBackingUp] = useState(false);

  // Request Permissions
  useEffect(() => {
    (async () => {
      const { status } = await MediaLibrary.requestPermissionsAsync();
      setHasPermission(status === 'granted');
      if (status === 'granted') {
        loadGallery();
      }
    })();
  }, []);

  const loadGallery = async () => {
    const album = await MediaLibrary.getAssetsAsync({
      first: 20,
      sortBy: ['creationTime'],
    });
    setAssets(album.assets);
  };

  const uploadToTelegram = async (fileUri, fileName) => {
    if (!token || !chatId) {
      Alert.alert("Error", "Por favor ingresa el Token y el Chat ID");
      return;
    }

    try {
      const apiUrl = `https://api.telegram.org/bot${token}/sendDocument`;
      
      const formData = new FormData();
      formData.append('chat_id', chatId);
      formData.append('document', {
        uri: fileUri,
        name: fileName || 'file.jpg',
        type: 'image/jpeg',
      });

      await axios.post(apiUrl, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      console.log(`Uploaded: ${fileName}`);
    } catch (error) {
      console.error("Upload failed", error);
    }
  };

  const startAutoBackup = async () => {
    if (!token || !chatId) {
      Alert.alert("Configuración incompleta", "Pon tu Token y Chat ID primero.");
      return;
    }
    
    setIsBackingUp(true);
    Alert.alert("Respaldo Iniciado", "Se están subiendo tus archivos a Telegram...");

    for (let asset of assets) {
      const info = await MediaLibrary.getAssetInfoAsync(asset);
      await uploadToTelegram(info.localUri || info.uri, info.filename);
    }
    
    setIsBackingUp(false);
    Alert.alert("Completado", "Todos los archivos han sido respaldados.");
  };

  if (hasPermission === null) {
    return <View style={styles.container}><Text>Pidiendo permisos...</Text></View>;
  }
  if (hasPermission === false) {
    return <View style={styles.container}><Text>No hay acceso a la galería.</Text></View>;
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Telegram Backup Gallery</Text>
      
      <View style={styles.configBox}>
        <TextInput
          style={styles.input}
          placeholder="Telegram Bot Token"
          value={token}
          onChangeText={setToken}
        />
        <TextInput
          style={styles.input}
          placeholder="Telegram Chat ID"
          value={chatId}
          onChangeText={setChatId}
        />
        <TouchableOpacity 
          style={[styles.button, isBackingUp && {backgroundColor: '#ccc'}]} 
          onPress={startAutoBackup}
          disabled={isBackingUp}
        >
          <Text style={styles.buttonText}>{isBackingUp ? 'Respaldando...' : 'Iniciar Respaldo Automático'}</Text>
        </TouchableOpacity>
      </View>

      <FlatList
        data={assets}
        keyExtractor={(item) => item.id}
        numColumns={3}
        renderItem={({ item }) => (
          <Image
            source={{ uri: item.uri }}
            style={styles.image}
          />
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
    paddingTop: 50,
  },
  title: {
    fontSize: 22,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 20,
  },
  configBox: {
    padding: 20,
    backgroundColor: '#f0f0f0',
    margin: 10,
    borderRadius: 10,
  },
  input: {
    backgroundColor: '#fff',
    padding: 10,
    marginBottom: 10,
    borderRadius: 5,
    borderWidth: 1,
    borderColor: '#ddd',
  },
  button: {
    backgroundColor: '#0088cc',
    padding: 15,
    borderRadius: 5,
    alignItems: 'center',
  },
  buttonText: {
    color: '#fff',
    fontWeight: 'bold',
  },
  image: {
    width: '33%',
    height: 120,
    margin: 1,
  },
});
